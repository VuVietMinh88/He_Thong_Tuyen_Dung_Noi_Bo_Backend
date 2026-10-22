package vn.ttcs.recruitment.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests both historical upgrade paths on disposable databases, never on the configured development database. */
class MigrationLineageUpgradeTest {
    private static final Timestamp AT = Timestamp.from(Instant.parse("2026-10-18T03:00:00Z"));
    private static final List<HikariDataSource> DATABASES = new ArrayList<>();
    private static EmbeddedPostgres postgres;

    @BeforeAll
    static void startPostgres() throws Exception {
        postgres = EmbeddedPostgres.builder().setPort(0).setServerConfig("listen_addresses", "127.0.0.1").start();
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        DATABASES.forEach(HikariDataSource::close);
        // This cluster contains many migrated databases. Flush their data before embedded-postgres's fixed
        // five-second pg_ctl shutdown wait; otherwise its cleanup can race the still-running shutdown checkpoint.
        try (var connection = postgres.getPostgresDatabase().getConnection();
             var statement = connection.createStatement()) {
            statement.setQueryTimeout(60);
            statement.execute("CHECKPOINT");
        } finally {
            postgres.close();
        }
    }

    @Test
    void bothFreshPathsProduceTheSameBusinessSchemaAndPermissionSeed() {
        DataSource s5 = newDatabase();
        DataSource feedback = newDatabase();
        for (var entry : Map.of("s5", s5, "feedback", feedback).entrySet()) {
            Flyway flyway = configured(entry.getValue(), entry.getKey()).load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(50);
            flyway.validate();
            assertThat(flyway.migrate().migrationsExecuted).isZero();
        }
        assertThat(schema(new JdbcTemplate(s5))).isEqualTo(schema(new JdbcTemplate(feedback)));
        assertThat(rows(new JdbcTemplate(s5), "role_permissions"))
                .isEqualTo(rows(new JdbcTemplate(feedback), "role_permissions"));
        assertUnifiedFeatures(new JdbcTemplate(s5));
        assertUnifiedFeatures(new JdbcTemplate(feedback));
    }

    @Test
    void s5V41UpgradeKeepsAccountsCvBytesApplicationsHistoryAndScreening() {
        DataSource dataSource = newDatabase();
        configured(dataSource, "s5").target("41").load().migrate();
        var jdbc = new JdbcTemplate(dataSource);
        Fixture fixture = baseFixture(jdbc);
        UUID application = application(jdbc, fixture);
        byte[] cv = "%PDF-1.4\nlocal migration preservation fixture".getBytes(StandardCharsets.UTF_8);
        jdbc.update("""
                INSERT INTO application_cvs(application_id,filename,content_type,size_bytes,sha256,content,received_at)
                VALUES (?,'ho-so.pdf','application/pdf',?,pg_catalog.sha256(?),?,?)
                """, application, cv.length, cv, cv, AT);
        UUID candidate = UUID.randomUUID();
        jdbc.update("INSERT INTO candidates(id,normalized_email,created_at) VALUES (?,'candidate@example.test',?)",
                candidate, AT);
        jdbc.update("INSERT INTO candidate_applications(application_id,candidate_id,linked_at) VALUES (?,?,?)",
                application, candidate, AT);
        jdbc.update("""
                INSERT INTO application_stage_events(id,application_id,stage_before,stage_after,actor_type,actor_id,
                    reason,occurred_at) VALUES (?,?,'NEW','SCREENING','STAFF',?,'Ghi chú trước nâng cấp',?)
                """, UUID.randomUUID(), application, fixture.account(), AT);
        UUID tag = catalog(jdbc, "CANDIDATE_TAG");
        jdbc.update("INSERT INTO application_screening_tags(application_id,tag_id,added_by,added_at) VALUES (?,?,?,?)",
                application, tag, fixture.account(), AT);
        jdbc.update("INSERT INTO application_screening_ratings(application_id,rating,updated_by,updated_at) "
                + "VALUES (?,4,?,?)", application, fixture.account(), AT);
        jdbc.update("INSERT INTO application_screening_notes(id,application_id,content,author_id,created_at) "
                + "VALUES (?,?,'Nhận xét nội bộ cần giữ',?,?)", UUID.randomUUID(), application, fixture.account(), AT);
        var before = dataSnapshot(jdbc);
        var history = history(jdbc);

        Flyway upgrade = configured(dataSource, "s5").load();
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(10);
        upgrade.validate();
        assertPreserved(jdbc, before, history);
        assertThat(jdbc.queryForObject("SELECT content FROM application_cvs WHERE application_id=?", byte[].class,
                application)).isEqualTo(cv);
        assertUnifiedFeatures(jdbc);
        assertThat(upgrade.migrate().migrationsExecuted).isZero();
    }

    @Test
    void feedbackV39UpgradeKeepsPostingStatesAppendOnlyHistoryAndBrowserCounters() {
        DataSource dataSource = newDatabase();
        configured(dataSource, "feedback").target("39").load().migrate();
        var jdbc = new JdbcTemplate(dataSource);
        Fixture fixture = baseFixture(jdbc);
        publishAndFinish(jdbc, fixture, "EXPIRED");
        Fixture second = anotherPosting(jdbc, fixture.account());
        publishAndFinish(jdbc, second, "UNPUBLISHED");
        jdbc.update("""
                INSERT INTO password_reset_devices(id,request_count,window_started_at,locked_until,updated_at)
                VALUES (?,5,?,?,?)
                """, UUID.randomUUID(), AT, Timestamp.from(AT.toInstant().plusSeconds(900)), AT);
        var before = dataSnapshot(jdbc);
        var history = history(jdbc);

        Flyway upgrade = configured(dataSource, "feedback").load();
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(19);
        upgrade.validate();
        assertPreserved(jdbc, before, history);
        assertUnifiedFeatures(jdbc);
        assertThat(upgrade.migrate().migrationsExecuted).isZero();
        assertThatThrownBy(() -> jdbc.update("UPDATE job_posting_events SET comment='changed'"))
                .hasMessageContaining("job_posting_events is append-only");
    }

    @ParameterizedTest
    @CsvSource({"s5,25", "s5,26", "s5,27", "feedback,25", "feedback,26", "feedback,27"})
    void partialHistoricalUpgradesKeepAlreadySavedDataAndHistory(String lineage, String version) {
        DataSource dataSource = newDatabase();
        configured(dataSource, lineage).target(version).load().migrate();
        var jdbc = new JdbcTemplate(dataSource);
        Fixture fixture = baseFixture(jdbc);
        UUID application = null;
        if (lineage.equals("s5") && !version.equals("25")) {
            application = application(jdbc, fixture);
        }
        var before = dataSnapshot(jdbc);
        var history = history(jdbc);

        Flyway upgrade = configured(dataSource, lineage).load();
        upgrade.migrate();
        upgrade.validate();
        assertPreserved(jdbc, before, history);
        assertUnifiedFeatures(jdbc);
        if (application != null) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_applications WHERE application_id=?",
                    Integer.class, application)).isEqualTo(1);
        }
        assertThat(upgrade.migrate().migrationsExecuted).isZero();
    }

    @ParameterizedTest
    @CsvSource({"s5,41,feedback", "feedback,39,s5", "s5,26,feedback", "feedback,26,s5"})
    void wrongLineageFailsValidationBeforeAnyBridgeDdl(String original, String version, String wrong) {
        DataSource dataSource = newDatabase();
        configured(dataSource, original).target(version).load().migrate();
        var jdbc = new JdbcTemplate(dataSource);
        baseFixture(jdbc);
        var before = dataSnapshot(jdbc);
        var history = history(jdbc);
        var schema = schema(jdbc);

        assertThatThrownBy(() -> configured(dataSource, wrong).load().migrate())
                .isInstanceOf(FlywayException.class).hasMessageContaining("Migration");
        assertThat(schema(jdbc)).isEqualTo(schema);
        assertThat(history(jdbc)).isEqualTo(history);
        assertPreserved(jdbc, before, history);
    }

    @Test
    void changedHistoricalScriptFailsBeforeAnyBridgeWithoutEditingHistory(@TempDir Path changedScripts)
            throws Exception {
        DataSource dataSource = newDatabase();
        configured(dataSource, "s5").target("41").load().migrate();
        var jdbc = new JdbcTemplate(dataSource);
        baseFixture(jdbc);
        var history = history(jdbc);
        var before = dataSnapshot(jdbc);
        var schema = schema(jdbc);
        var resolver = new PathMatchingResourcePatternResolver();
        for (var resource : resolver.getResources("classpath:db/migration/V*__*.sql")) {
            try (var input = resource.getInputStream()) {
                Files.copy(input, changedScripts.resolve(resource.getFilename()));
            }
        }
        Path v1 = changedScripts.resolve("V1__create_accounts_and_sessions.sql");
        String original = Files.readString(v1);
        String changed = original.replace("enabled BOOLEAN NOT NULL DEFAULT TRUE",
                "enabled BOOLEAN NOT NULL DEFAULT FALSE");
        assertThat(changed).isNotEqualTo(original);
        Files.writeString(v1, changed);

        assertThatThrownBy(() -> configured(dataSource, "s5")
                .locations("filesystem:" + changedScripts.toAbsolutePath()).load().migrate())
                .isInstanceOf(FlywayException.class).hasMessageContaining("checksum mismatch");
        assertThat(schema(jdbc)).isEqualTo(schema);
        assertThat(history(jdbc)).isEqualTo(history);
        assertPreserved(jdbc, before, history);
    }

    @Test
    void s5DuplicatePhonesStopAtNewConstraintWithoutChangingAccountsAndCanResumeAfterCorrection() {
        DataSource dataSource = newDatabase();
        configured(dataSource, "s5").target("41").load().migrate();
        var jdbc = new JdbcTemplate(dataSource);
        UUID first = account(jdbc, "first@example.test", "0912345678");
        account(jdbc, "second@example.test", "0912345678");
        var accounts = rows(jdbc, "user_accounts");
        var originalHistory = history(jdbc);

        assertThatThrownBy(() -> configured(dataSource, "s5").load().migrate())
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("so dien thoai dang dung cho nhieu tai khoan")
                .hasMessageNotContaining("0912345678").hasMessageNotContaining("@example.test");
        assertThat(rows(jdbc, "user_accounts")).isEqualTo(accounts);
        assertThat(history(jdbc)).startsWith(originalHistory.toArray(String[]::new));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='44' AND success",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conname='user_accounts_phone_key'",
                Integer.class)).isZero();

        // This edit is deliberate test fixture setup. Production requires staff to resolve the duplicate first.
        jdbc.update("UPDATE user_accounts SET phone=NULL WHERE id=?", first);
        Flyway retry = configured(dataSource, "s5").load();
        assertThat(retry.migrate().migrationsExecuted).isEqualTo(8);
        retry.validate();
        assertThat(retry.migrate().migrationsExecuted).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"s5", "feedback"})
    void migrationConfigurationKeepsValidationEnabledEvenIfUnsafeFlywayFlagsWereSupplied(String lineage) {
        DataSource dataSource = newDatabase();
        FluentConfiguration configuration = Flyway.configure().dataSource(dataSource)
                .outOfOrder(true).baselineOnMigrate(true).validateOnMigrate(false).cleanDisabled(false)
                .ignoreMigrationPatterns("*:missing", "*:future");
        new MigrationLineageConfiguration(lineage).customize(configuration);
        assertThat(configuration.isOutOfOrder()).isFalse();
        assertThat(configuration.isBaselineOnMigrate()).isFalse();
        assertThat(configuration.isValidateOnMigrate()).isTrue();
        assertThat(configuration.isCleanDisabled()).isTrue();
        assertThat(configuration.getIgnoreMigrationPatterns()).isEmpty();
    }

    private static FluentConfiguration configured(DataSource dataSource, String lineage) {
        FluentConfiguration configuration = Flyway.configure().dataSource(dataSource);
        new MigrationLineageConfiguration(lineage).customize(configuration);
        return configuration;
    }

    private static DataSource newDatabase() {
        String name = "migration_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(postgres.getPostgresDatabase()).execute("CREATE DATABASE " + name);
        var configuration = new HikariConfig();
        configuration.setDataSource(postgres.getDatabase("postgres", name));
        configuration.setMaximumPoolSize(3);
        configuration.setMinimumIdle(0);
        var dataSource = new HikariDataSource(configuration);
        DATABASES.add(dataSource);
        return dataSource;
    }

    private static List<String> history(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT h::text FROM flyway_schema_history h ORDER BY installed_rank", String.class);
    }

    private static Map<String, List<String>> dataSnapshot(JdbcTemplate jdbc) {
        var snapshot = new LinkedHashMap<String, List<String>>();
        for (String table : jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public' "
                + "AND tablename NOT IN ('flyway_schema_history','role_permissions') ORDER BY tablename", String.class)) {
            snapshot.put(table, rows(jdbc, table));
        }
        return snapshot;
    }

    private static List<String> rows(JdbcTemplate jdbc, String table) {
        return jdbc.queryForList("SELECT t::text FROM \"" + table.replace("\"", "\"\"")
                + "\" t ORDER BY t::text", String.class);
    }

    private static void assertPreserved(JdbcTemplate jdbc, Map<String, List<String>> before, List<String> history) {
        before.forEach((table, rows) -> assertThat(rows(jdbc, table)).as("existing rows of %s", table).isEqualTo(rows));
        assertThat(history(jdbc)).startsWith(history.toArray(String[]::new));
    }

    private static Map<String, List<Map<String, Object>>> schema(JdbcTemplate jdbc) {
        var schema = new LinkedHashMap<String, List<Map<String, Object>>>();
        schema.put("columns", jdbc.queryForList("""
                SELECT table_name,column_name,data_type,udt_name,is_nullable,column_default,character_maximum_length,
                       numeric_precision,numeric_scale,datetime_precision,is_identity,identity_generation
                FROM information_schema.columns WHERE table_schema='public' AND table_name<>'flyway_schema_history'
                ORDER BY table_name,column_name
                """));
        schema.put("constraints", jdbc.queryForList("""
                SELECT c.relname,k.conname,k.contype,pg_get_constraintdef(k.oid) AS definition
                FROM pg_constraint k JOIN pg_class c ON c.oid=k.conrelid JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname='public' AND c.relname<>'flyway_schema_history' ORDER BY c.relname,k.conname
                """));
        schema.put("indexes", jdbc.queryForList("""
                SELECT tablename,indexname,indexdef FROM pg_indexes
                WHERE schemaname='public' AND tablename<>'flyway_schema_history' ORDER BY tablename,indexname
                """));
        schema.put("triggers", jdbc.queryForList("""
                SELECT c.relname,t.tgname,pg_get_triggerdef(t.oid) AS definition FROM pg_trigger t
                JOIN pg_class c ON c.oid=t.tgrelid JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname='public' AND NOT t.tgisinternal ORDER BY c.relname,t.tgname
                """));
        schema.put("functions", jdbc.queryForList("""
                SELECT p.proname,pg_get_functiondef(p.oid) AS definition FROM pg_proc p
                JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='public' ORDER BY p.proname
                """));
        return schema;
    }

    private static void assertUnifiedFeatures(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema='public' "
                + "AND table_name IN ('job_applications','application_cvs','application_stage_events',"
                + "'application_screening_notes','password_reset_devices')", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conname='user_accounts_phone_key'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT permission_code FROM role_permissions WHERE role_code='APPROVER' "
                + "AND permission_code LIKE 'CANDIDATES_READ_%'", String.class))
                .containsExactly("CANDIDATES_READ_SCOPED");
        assertThat(jdbc.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                + "WHERE conname='valid_job_posting_status'", String.class)).contains("UNPUBLISHED", "EXPIRED");
    }

    private static UUID account(JdbcTemplate jdbc, String email, String phone) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO user_accounts(id,email,full_name,password_hash,phone,created_at) "
                + "VALUES (?,?,'Nhân sự','test-only',?,?)", id, email, phone, AT);
        return id;
    }

    private static Fixture baseFixture(JdbcTemplate jdbc) {
        return anotherPosting(jdbc, account(jdbc, "staff@example.test", "0987654321"));
    }

    private static Fixture anotherPosting(JdbcTemplate jdbc, UUID account) {
        UUID department = UUID.randomUUID();
        UUID position = UUID.randomUUID();
        UUID requisition = UUID.randomUUID();
        UUID posting = UUID.randomUUID();
        jdbc.update("INSERT INTO departments(id,code,name,manager_user_id) VALUES (?,?, 'Phòng kỹ thuật',?)",
                department, "IT-" + department, account);
        jdbc.update("INSERT INTO positions(id,code,name,level,salary_min,salary_max,created_at,updated_at) "
                + "VALUES (?,?,'Lập trình viên','Junior',0,0,?,?)", position, "DEV-" + position, AT, AT);
        jdbc.update("""
                INSERT INTO recruitment_requisitions(id,position_id,department_id,headcount,reason,status,created_by,
                    created_at,updated_at) VALUES (?,?,?,1,'NEW_HEADCOUNT','DRAFT',?,?,?)
                """, requisition, position, department, account, AT, AT);
        jdbc.update("""
                INSERT INTO job_postings(id,requisition_id,status,revision,title,job_description,candidate_requirements,
                    show_salary,created_by,created_at,updated_at)
                VALUES (?,?,'DRAFT',1,'Lập trình viên','Mô tả','Yêu cầu',false,?,?,?)
                """, posting, requisition, account, AT, AT);
        return new Fixture(account, requisition, posting);
    }

    private static UUID application(JdbcTemplate jdbc, Fixture fixture) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO job_applications(id,job_posting_id,requisition_id,full_name,email,normalized_email,phone,
                    stage,received_at,posting_title,tracking_code_hash)
                VALUES (?,?,?,'Ứng viên','candidate@example.test','candidate@example.test','0912345678',
                    'SCREENING',?,'Lập trình viên',pg_catalog.sha256(?))
                """, id, fixture.posting(), fixture.requisition(), AT, id.toString().getBytes(StandardCharsets.UTF_8));
        return id;
    }

    private static UUID catalog(JdbcTemplate jdbc, String type) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO recruitment_catalog_items(id,catalog_type,code,name,sort_order,created_at,updated_at) "
                + "VALUES (?,?,?,'Giá trị danh mục',0,?,?)", id, type, id.toString(), AT, AT);
        return id;
    }

    private static void publishAndFinish(JdbcTemplate jdbc, Fixture fixture, String status) {
        UUID location = catalog(jdbc, "WORK_LOCATION");
        UUID employment = catalog(jdbc, "EMPLOYMENT_TYPE");
        jdbc.update("""
                UPDATE job_postings SET status=?,revision=3,work_location_id=?,employment_type_id=?,
                    application_deadline=DATE '2026-10-01',published_by=?,published_at=? WHERE id=?
                """, status, location, employment, fixture.account(), AT, fixture.posting());
        jdbc.update("""
                INSERT INTO job_posting_events(id,job_posting_id,revision,event_type,status_before,status_after,
                    actor_id,occurred_at) VALUES (?,?,2,'PUBLISHED','APPROVED','PUBLISHED',?,?)
                """, UUID.randomUUID(), fixture.posting(), fixture.account(), AT);
        jdbc.update("""
                INSERT INTO job_posting_events(id,job_posting_id,revision,event_type,status_before,status_after,
                    actor_id,occurred_at,comment) VALUES (?,?,3,?,'PUBLISHED',?,CAST(? AS uuid),?,CAST(? AS text))
                """, UUID.randomUUID(), fixture.posting(), status, status,
                status.equals("EXPIRED") ? null : fixture.account(), AT,
                status.equals("EXPIRED") ? null : "Tạm gỡ theo yêu cầu");
    }

    private record Fixture(UUID account, UUID requisition, UUID posting) { }
}
