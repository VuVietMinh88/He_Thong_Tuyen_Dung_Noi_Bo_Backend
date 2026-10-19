package vn.ttcs.recruitment.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V64 is exercised on both histories using temporary PostgreSQL, including failures at transaction commit. */
class OnboardingMigrationIntegrationTest {
    private static final Timestamp AT = Timestamp.from(Instant.parse("2026-10-19T03:00:00Z"));
    private static final List<HikariDataSource> DATABASES = new ArrayList<>();
    private static final Map<String, JdbcTemplate> LINEAGES = new LinkedHashMap<>();
    private static EmbeddedPostgres postgres;

    @BeforeAll
    static void startPostgres() throws Exception {
        postgres = EmbeddedPostgres.builder().setPort(0).setServerConfig("listen_addresses", "127.0.0.1").start();
        for (String lineage : List.of("s5", "feedback")) {
            DataSource source = newDatabase();
            Flyway fresh = configured(source, lineage).target("64").load();
            assertThat(fresh.migrate().migrationsExecuted).isEqualTo(54);
            fresh.validate();
            assertThat(fresh.migrate().migrationsExecuted).isZero();
            LINEAGES.put(lineage, new JdbcTemplate(source));
        }
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        DATABASES.forEach(HikariDataSource::close);
        if (postgres == null) return;
        try (var connection = postgres.getPostgresDatabase().getConnection(); var statement = connection.createStatement()) {
            statement.setQueryTimeout(60);
            statement.execute("CHECKPOINT");
        } finally {
            postgres.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"s5", "feedback"})
    void upgradePreservesHiredApplicationsAcceptedOffersAndHistory(String lineage) {
        DataSource source = newDatabase();
        configured(source, lineage).target("63").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        Fixture f = fixture(jdbc);
        UUID offer = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO offers(id,application_id,candidate_id,position_id,candidate_name,email,position_title,
                    salary,allowance,start_date,response_deadline,band_min,band_max,outside_band,status,
                    created_by,created_at,updated_at,sent_at,responded_at,response_note)
                VALUES (?,?,?,?,'Candidate',?,'Developer',15000000,0,DATE '2026-11-01',?,10000000,20000000,false,
                    'ACCEPTED',?,?,?,?,?,'Accepted before onboarding existed')
                """, offer, f.application(), f.candidate(), f.position(), f.candidate() + "@example.test",
                Timestamp.from(AT.toInstant().plusSeconds(86400)), f.actor(), AT, AT, AT, AT);
        jdbc.update("""
                INSERT INTO application_stage_events(id,application_id,stage_before,stage_after,actor_type,occurred_at,offer_id)
                VALUES (?,?,'OFFER','HIRED','CANDIDATE',?,?)
                """, UUID.randomUUID(), f.application(), AT, offer);
        var before = new LinkedHashMap<String, List<String>>();
        for (String table : List.of("user_accounts", "job_applications", "offers", "application_stage_events", "role_permissions")) {
            before.put(table, rows(jdbc, table));
        }
        Flyway upgrade = configured(source, lineage).target("64").load();
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        upgrade.validate();
        before.forEach((table, data) -> assertThat(rows(jdbc, table)).as("preserved %s", table).isEqualTo(data));
        for (String table : List.of("onboarding_templates", "onboarding_template_items", "onboarding_checklists", "onboarding_checklist_items")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class)).isZero();
        }
        assertThat(upgrade.migrate().migrationsExecuted).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"s5", "feedback"})
    void templatesNeedItemsAndEnforceBoundsForeignKeysAndDepartmentIdentity(String lineage) {
        JdbcTemplate jdbc = LINEAGES.get(lineage);
        Fixture f = fixture(jdbc);
        UUID template = UUID.randomUUID();
        rejected("23514", () -> transaction(jdbc, () -> insertTemplate(jdbc, f, template)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM onboarding_templates WHERE id=?", Integer.class, template)).isZero();
        UUID valid = template(jdbc, f);
        rejected("23514", () -> jdbc.update("DELETE FROM onboarding_template_items WHERE template_id=?", valid));
        rejected("23514", () -> jdbc.update("UPDATE onboarding_template_items SET due_offset_days=366 WHERE template_id=?", valid));
        rejected("23514", () -> jdbc.update("UPDATE onboarding_template_items SET due_offset_days=-31 WHERE template_id=?", valid));
        rejected("23514", () -> jdbc.update("UPDATE onboarding_templates SET name='   ' WHERE id=?", valid));
        rejected("23514", () -> jdbc.update("UPDATE onboarding_templates SET department_id=? WHERE id=?", UUID.randomUUID(), valid));
        rejected("23503", () -> jdbc.update("UPDATE onboarding_template_items SET assignee_id=? WHERE template_id=?", UUID.randomUUID(), valid));
        rejected("23503", () -> jdbc.update("DELETE FROM departments WHERE id=?", f.department()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM onboarding_template_items WHERE template_id=?", Integer.class, valid)).isEqualTo(1);
        transaction(jdbc, () -> {
            jdbc.update("DELETE FROM onboarding_template_items WHERE template_id=?", valid);
            insertTemplateItem(jdbc, valid, f.actor(), "Replacement item", 365, 0);
        });
        assertThat(jdbc.queryForObject("SELECT title FROM onboarding_template_items WHERE template_id=?", String.class, valid)).isEqualTo("Replacement item");
    }

    @ParameterizedTest
    @ValueSource(strings = {"s5", "feedback"})
    void snapshotsKeepOldTemplateItemsAndPermitOnlyOneChecklistPerApplication(String lineage) {
        JdbcTemplate jdbc = LINEAGES.get(lineage);
        Fixture f = fixture(jdbc);
        UUID template = template(jdbc, f);
        UUID checklist = checklist(jdbc, f, template);
        jdbc.update("UPDATE onboarding_templates SET name='Revised template',version=version+1 WHERE id=?", template);
        jdbc.update("UPDATE onboarding_template_items SET title='Updated equipment' WHERE template_id=?", template);
        assertThat(jdbc.queryForMap("SELECT template_name,template_version FROM onboarding_checklists WHERE id=?", checklist))
                .containsEntry("template_name", "Engineering starter").containsEntry("template_version", 0L);
        assertThat(jdbc.queryForList("SELECT title FROM onboarding_checklist_items WHERE checklist_id=? ORDER BY sort_order", String.class, checklist))
                .containsExactly("Prepare account", "Prepare equipment");
        rejected("23505", () -> checklist(jdbc, f, template));
        rejected("23514", () -> jdbc.update("UPDATE onboarding_checklists SET start_date=start_date+1 WHERE id=?", checklist));
        rejected("23514", () -> jdbc.update("UPDATE onboarding_checklists SET template_name='Changed snapshot' WHERE id=?", checklist));
        rejected("23514", () -> jdbc.update("UPDATE onboarding_checklists SET application_id=? WHERE id=?", UUID.randomUUID(), checklist));
        rejected("23514", () -> jdbc.update("UPDATE onboarding_checklist_items SET checklist_id=? WHERE checklist_id=?", UUID.randomUUID(), checklist));
    }

    @ParameterizedTest
    @ValueSource(strings = {"s5", "feedback"})
    void emptyWrongDepartmentAndOutOfRangeChecklistsCannotCommit(String lineage) {
        JdbcTemplate jdbc = LINEAGES.get(lineage);
        Fixture f = fixture(jdbc);
        UUID template = template(jdbc, f);
        rejected("23514", () -> transaction(jdbc, () -> insertChecklist(jdbc, f, template, UUID.randomUUID())));
        Fixture other = fixture(jdbc);
        UUID otherTemplate = template(jdbc, other);
        rejected("23514", () -> checklist(jdbc, f, otherTemplate));
        UUID checklist = checklist(jdbc, f, template);
        rejected("23514", () -> jdbc.update("DELETE FROM onboarding_checklist_items WHERE checklist_id=?", checklist));
        rejected("23514", () -> jdbc.update("UPDATE onboarding_checklist_items SET due_date=DATE '2026-09-01' WHERE checklist_id=?", checklist));
        rejected("23514", () -> jdbc.update("UPDATE onboarding_checklist_items SET due_date=DATE '2028-01-01' WHERE checklist_id=?", checklist));
        rejected("23514", () -> jdbc.update("UPDATE job_applications SET stage='REJECTED' WHERE id=?", f.application()));
        assertThat(jdbc.queryForObject("SELECT stage FROM job_applications WHERE id=?", String.class, f.application())).isEqualTo("HIRED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM onboarding_checklist_items WHERE checklist_id=?", Integer.class, checklist)).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"s5", "feedback"})
    void completionRequiresAllItemsAndBothAggregateAndApplicationInOneTransaction(String lineage) {
        JdbcTemplate jdbc = LINEAGES.get(lineage);
        Fixture f = fixture(jdbc);
        UUID checklist = checklist(jdbc, f, template(jdbc, f));
        rejected("23514", () -> markChecklistComplete(jdbc, f, checklist));
        rejected("23514", () -> jdbc.update("UPDATE job_applications SET stage='ONBOARDED' WHERE id=?", f.application()));
        rejected("23514", () -> markItemsDone(jdbc, f, checklist));
        rejected("23514", () -> transaction(jdbc, () -> {
            markItemsDone(jdbc, f, checklist);
            markChecklistComplete(jdbc, f, checklist);
        }));
        assertThat(jdbc.queryForObject("SELECT status FROM onboarding_checklists WHERE id=?", String.class, checklist)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM onboarding_checklist_items WHERE checklist_id=? AND status='DONE'", Integer.class, checklist)).isZero();
        complete(jdbc, f, checklist);
        assertThat(jdbc.queryForObject("SELECT stage FROM job_applications WHERE id=?", String.class, f.application())).isEqualTo("ONBOARDED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_stage_events WHERE application_id=? AND stage_after='ONBOARDED'", Integer.class, f.application())).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"s5", "feedback"})
    void finalChecklistAndItsItemsAndApplicationStageCannotBeReopenedOrDeleted(String lineage) {
        JdbcTemplate jdbc = LINEAGES.get(lineage);
        Fixture f = fixture(jdbc);
        UUID checklist = checklist(jdbc, f, template(jdbc, f));
        complete(jdbc, f, checklist);
        rejected("23514", () -> jdbc.update("UPDATE onboarding_checklists SET status='PENDING',completed_at=NULL,completed_by=NULL WHERE id=?", checklist));
        rejected("23514", () -> jdbc.update("UPDATE onboarding_checklists SET version=version+1 WHERE id=?", checklist));
        rejected("23514", () -> jdbc.update("DELETE FROM onboarding_checklists WHERE id=?", checklist));
        rejected("23514", () -> jdbc.update("UPDATE onboarding_checklist_items SET title='Changed after completion' WHERE checklist_id=?", checklist));
        rejected("23514", () -> jdbc.update("DELETE FROM onboarding_checklist_items WHERE checklist_id=?", checklist));
        rejected("23514", () -> insertChecklistItem(jdbc, checklist, f.actor(), "Late item", 2));
        rejected("23514", () -> jdbc.update("UPDATE job_applications SET stage='HIRED' WHERE id=?", f.application()));
        rejected("23514", () -> jdbc.update("UPDATE job_applications SET stage='REJECTED' WHERE id=?", f.application()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"s5", "feedback"})
    void openItemCanBeReopenedOnlyWithClearedCompletionMetadata(String lineage) {
        JdbcTemplate jdbc = LINEAGES.get(lineage);
        Fixture f = fixture(jdbc);
        UUID checklist = checklist(jdbc, f, template(jdbc, f));
        UUID item = jdbc.queryForObject("SELECT id FROM onboarding_checklist_items WHERE checklist_id=? AND sort_order=0", UUID.class, checklist);
        rejected("23514", () -> jdbc.update("UPDATE onboarding_checklist_items SET status='DONE' WHERE id=?", item));
        jdbc.update("UPDATE onboarding_checklist_items SET status='DONE',completed_at=?,completed_by=? WHERE id=?", AT, f.actor(), item);
        rejected("23514", () -> jdbc.update("UPDATE onboarding_checklist_items SET status='PENDING' WHERE id=?", item));
        jdbc.update("UPDATE onboarding_checklist_items SET status='IN_PROGRESS',completed_at=NULL,completed_by=NULL WHERE id=?", item);
        assertThat(jdbc.queryForMap("SELECT status,completed_at,completed_by FROM onboarding_checklist_items WHERE id=?", item))
                .containsEntry("status", "IN_PROGRESS").containsEntry("completed_at", null).containsEntry("completed_by", null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"s5", "feedback"})
    void stageCannotSkipHiredOrClaimOnboardedWithoutAChecklist(String lineage) {
        JdbcTemplate jdbc = LINEAGES.get(lineage);
        Fixture f = fixture(jdbc);
        rejected("23514", () -> jdbc.update("UPDATE job_applications SET stage='ONBOARDED' WHERE id=?", f.application()));
        jdbc.update("UPDATE job_applications SET stage='OFFER' WHERE id=?", f.application());
        rejected("23514", () -> jdbc.update("UPDATE job_applications SET stage='ONBOARDED' WHERE id=?", f.application()));
        assertThat(jdbc.queryForObject("SELECT stage FROM job_applications WHERE id=?", String.class, f.application())).isEqualTo("OFFER");
    }

    private static Fixture fixture(JdbcTemplate jdbc) {
        UUID actor = UUID.randomUUID();
        UUID department = UUID.randomUUID();
        UUID position = UUID.randomUUID();
        UUID requisition = UUID.randomUUID();
        UUID posting = UUID.randomUUID();
        UUID application = UUID.randomUUID();
        UUID candidate = UUID.randomUUID();
        jdbc.update("INSERT INTO user_accounts(id,email,full_name,password_hash,created_at) VALUES (?,?,'Staff','test-only',?)", actor, actor + "@example.test", AT);
        jdbc.update("INSERT INTO departments(id,code,name,manager_user_id) VALUES (?,?,'Engineering',?)", department, "IT-" + department, actor);
        jdbc.update("INSERT INTO positions(id,code,name,level,salary_min,salary_max,created_at,updated_at) VALUES (?,?,'Developer','Junior',10000000,20000000,?,?)", position, "DEV-" + position, AT, AT);
        jdbc.update("INSERT INTO recruitment_requisitions(id,position_id,department_id,headcount,reason,status,created_by,created_at,updated_at) VALUES (?,?,?,1,'NEW_HEADCOUNT','DRAFT',?,?,?)", requisition, position, department, actor, AT, AT);
        jdbc.update("INSERT INTO job_postings(id,requisition_id,status,revision,title,job_description,candidate_requirements,show_salary,created_by,created_at,updated_at) VALUES (?,?,'DRAFT',1,'Developer','Description','Requirements',false,?,?,?)", posting, requisition, actor, AT, AT);
        jdbc.update("""
                INSERT INTO job_applications(id,job_posting_id,requisition_id,full_name,email,normalized_email,phone,stage,received_at,posting_title,tracking_code_hash)
                VALUES (?,?,?,'Candidate',?,?,'0912345678','HIRED',?,'Developer',pg_catalog.sha256(?))
                """, application, posting, requisition, candidate + "@example.test", candidate + "@example.test", AT, application.toString().getBytes(StandardCharsets.UTF_8));
        jdbc.update("INSERT INTO candidates(id,normalized_email,created_at) VALUES (?,?,?)", candidate, candidate + "@example.test", AT);
        jdbc.update("INSERT INTO candidate_applications(application_id,candidate_id,linked_at) VALUES (?,?,?)", application, candidate, AT);
        return new Fixture(actor, department, position, application, candidate);
    }

    private static UUID template(JdbcTemplate jdbc, Fixture f) {
        UUID id = UUID.randomUUID();
        transaction(jdbc, () -> {
            insertTemplate(jdbc, f, id);
            insertTemplateItem(jdbc, id, f.actor(), "Prepare account", -1, 0);
        });
        return id;
    }

    private static void insertTemplate(JdbcTemplate jdbc, Fixture f, UUID id) {
        jdbc.update("INSERT INTO onboarding_templates(id,department_id,name,active,created_by,created_at,updated_at) VALUES (?,?,'Engineering starter',true,?,?,?)", id, f.department(), f.actor(), AT, AT);
    }

    private static void insertTemplateItem(JdbcTemplate jdbc, UUID template, UUID actor, String title, int offset, int order) {
        jdbc.update("INSERT INTO onboarding_template_items(id,template_id,title,assignee_id,due_offset_days,sort_order) VALUES (?,?,?,?,?,?)", UUID.randomUUID(), template, title, actor, offset, order);
    }

    private static UUID checklist(JdbcTemplate jdbc, Fixture f, UUID template) {
        UUID id = UUID.randomUUID();
        transaction(jdbc, () -> {
            insertChecklist(jdbc, f, template, id);
            insertChecklistItem(jdbc, id, f.actor(), "Prepare account", 0);
            insertChecklistItem(jdbc, id, f.actor(), "Prepare equipment", 1);
        });
        return id;
    }

    private static void insertChecklist(JdbcTemplate jdbc, Fixture f, UUID template, UUID id) {
        jdbc.update("""
                INSERT INTO onboarding_checklists(id,application_id,department_id,template_id,template_version,template_name,start_date,created_by,created_at,updated_at)
                VALUES (?,?,?,?,0,'Engineering starter',DATE '2026-11-01',?,?,?)
                """, id, f.application(), f.department(), template, f.actor(), AT, AT);
    }

    private static void insertChecklistItem(JdbcTemplate jdbc, UUID checklist, UUID actor, String title, int order) {
        jdbc.update("INSERT INTO onboarding_checklist_items(id,checklist_id,title,assignee_id,due_date,sort_order) VALUES (?,?,?,?,DATE '2026-11-01',?)", UUID.randomUUID(), checklist, title, actor, order);
    }

    private static void markItemsDone(JdbcTemplate jdbc, Fixture f, UUID checklist) {
        jdbc.update("UPDATE onboarding_checklist_items SET status='DONE',completed_at=?,completed_by=? WHERE checklist_id=?", AT, f.actor(), checklist);
    }

    private static void markChecklistComplete(JdbcTemplate jdbc, Fixture f, UUID checklist) {
        jdbc.update("UPDATE onboarding_checklists SET status='COMPLETED',version=version+1,completed_at=?,completed_by=? WHERE id=?", AT, f.actor(), checklist);
    }

    private static void complete(JdbcTemplate jdbc, Fixture f, UUID checklist) {
        transaction(jdbc, () -> {
            markItemsDone(jdbc, f, checklist);
            markChecklistComplete(jdbc, f, checklist);
            jdbc.update("UPDATE job_applications SET stage='ONBOARDED' WHERE id=?", f.application());
            jdbc.update("""
                    INSERT INTO application_stage_events(id,application_id,stage_before,stage_after,actor_type,actor_id,reason,occurred_at)
                    VALUES (?,?,'HIRED','ONBOARDED','STAFF',?,'Completed onboarding checklist',?)
                    """, UUID.randomUUID(), f.application(), f.actor(), AT);
        });
    }

    private static void transaction(JdbcTemplate jdbc, Runnable operation) {
        new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())).executeWithoutResult(tx -> operation.run());
    }

    private static void rejected(String state, Runnable operation) {
        assertThatThrownBy(operation::run).rootCause().isInstanceOf(SQLException.class)
                .satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo(state));
    }

    private static List<String> rows(JdbcTemplate jdbc, String table) {
        return jdbc.queryForList("SELECT to_jsonb(t)::text FROM " + table + " t ORDER BY 1", String.class);
    }

    private static FluentConfiguration configured(DataSource source, String lineage) {
        FluentConfiguration configuration = Flyway.configure().dataSource(source);
        new MigrationLineageConfiguration(lineage).customize(configuration);
        return configuration;
    }

    private static DataSource newDatabase() {
        String name = "onboarding_migration_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(postgres.getPostgresDatabase()).execute("CREATE DATABASE " + name);
        var configuration = new HikariConfig();
        configuration.setDataSource(postgres.getDatabase("postgres", name));
        configuration.setMaximumPoolSize(3);
        configuration.setMinimumIdle(0);
        var source = new HikariDataSource(configuration);
        DATABASES.add(source);
        return source;
    }

    private record Fixture(UUID actor, UUID department, UUID position, UUID application, UUID candidate) { }
}
