package vn.ttcs.recruitment.auth;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 381 (story S5-03): V40 adds what a staff stage change decided to its V34 history row: a rejection's
 * REJECTION_REASON value (id, RESTRICT, and the name it had) and the HR exception flag (SCREENING straight to OFFER).
 * The CHECKs tie both to exactly the rows they belong to; existing rows keep their values and get neither.
 */
class StaffStageChangeDetailsMigrationTest {
    private static final MigrationVersion DETAILS_VERSION = MigrationVersion.fromVersion("40");
    private static final Timestamp AT = Timestamp.from(Instant.parse("2026-10-17T03:00:00Z"));
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String INSERT = """
            INSERT INTO application_stage_events (id, application_id, stage_before, stage_after, actor_type, actor_id,
                reason, occurred_at, rejection_reason_id, rejection_reason_name, interview_skipped)
            VALUES (?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?)
            """;

    private static EmbeddedPostgres postgres;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void startMigratedDatabase() throws Exception {
        postgres = startPostgres();
        var dataSource = postgres.getPostgresDatabase();
        flyway(dataSource).target(DETAILS_VERSION).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        postgres.close();
    }

    @BeforeEach
    void deleteData() {
        jdbc.update("DELETE FROM application_stage_events");
        jdbc.update("DELETE FROM candidate_applications");
        jdbc.update("DELETE FROM candidates");
        jdbc.update("DELETE FROM job_applications");
        jdbc.update("DELETE FROM job_postings");
        jdbc.update("DELETE FROM recruitment_requisitions");
        jdbc.update("DELETE FROM recruitment_catalog_items");
        jdbc.update("DELETE FROM positions");
        jdbc.update("DELETE FROM departments");
        jdbc.update("DELETE FROM user_accounts");
    }

    // The upgrade keeps every application and every withdrawal as it was; old rows name no reason and no exception,
    // and V34's trigger still refuses every change afterwards.
    @Test
    void theUpgradeKeepsEveryWithdrawalAsItWas() throws Exception {
        try (var upgradePostgres = startPostgres()) {
            var dataSource = upgradePostgres.getPostgresDatabase();
            flyway(dataSource).target(latestVersionBefore(dataSource, DETAILS_VERSION)).load().migrate();
            var db = new JdbcTemplate(dataSource);
            var fixture = insertFixture(db);
            UUID application = insertApplication(db, fixture, "a@example.test", "WITHDRAWN");
            db.update("""
                    INSERT INTO application_stage_events (id,application_id,stage_before,stage_after,actor_type,
                        actor_id,reason,occurred_at)
                    VALUES (?,?,'SCREENING','WITHDRAWN','CANDIDATE',NULL,'Lý do',?)
                    """, UUID.randomUUID(), application, AT);
            var applications = db.queryForList("SELECT a::text FROM job_applications a ORDER BY id", String.class);

            var flyway = flyway(dataSource).target(DETAILS_VERSION).load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            flyway.validate();

            assertThat(db.queryForList("SELECT a::text FROM job_applications a ORDER BY id", String.class))
                    .isEqualTo(applications);
            var event = db.queryForMap("SELECT reason, rejection_reason_id, rejection_reason_name, interview_skipped "
                    + "FROM application_stage_events");
            assertThat(event.get("reason")).isEqualTo("Lý do");
            assertThat(event.get("rejection_reason_id")).isNull();
            assertThat(event.get("rejection_reason_name")).isNull();
            assertThat(event.get("interview_skipped")).isEqualTo(false);
            assertThatThrownBy(() -> db.update("UPDATE application_stage_events SET interview_skipped = true"))
                    .hasMessageContaining("application_stage_events rows are never changed");
        }
    }

    // What the stage API writes: a staff rejection with its reason, the HR exception, a plain staff move, and still
    // the candidate's own withdrawal.
    @Test
    void theRowsTheStageApiWritesAreAccepted() {
        var fixture = insertFixture(jdbc);
        UUID reason = insertCatalogValue("REJECTION_REASON");
        insertEvent(insertApplication(jdbc, fixture, "a@example.test", "REJECTED"), "INTERVIEW", "REJECTED", "STAFF",
                fixture.account(), reason, "Chưa phù hợp", false);
        insertEvent(insertApplication(jdbc, fixture, "b@example.test", "OFFER"), "SCREENING", "OFFER", "STAFF",
                fixture.account(), null, null, true);
        insertEvent(insertApplication(jdbc, fixture, "c@example.test", "SCREENING"), "NEW", "SCREENING", "STAFF",
                fixture.account(), null, null, false);
        insertEvent(insertApplication(jdbc, fixture, "d@example.test", "WITHDRAWN"), "NEW", "WITHDRAWN", "CANDIDATE",
                null, null, null, false);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_stage_events", Integer.class)).isEqualTo(4);
    }

    @Test
    void theChecksRefuseEveryOtherShape() {
        var fixture = insertFixture(jdbc);
        UUID reason = insertCatalogValue("REJECTION_REASON");
        UUID application = insertApplication(jdbc, fixture, "a@example.test", "REJECTED");
        UUID staff = fixture.account();
        String rejection = "application_stage_events_rejection_reason";
        String skip = "application_stage_events_interview_skip";

        // A staff rejection names its reason, with both the id and a visible name.
        assertRejected(rejection, application, "NEW", "REJECTED", "STAFF", staff, null, null, false);
        assertRejected(rejection, application, "NEW", "REJECTED", "STAFF", staff, reason, null, false);
        assertRejected(rejection, application, "NEW", "REJECTED", "STAFF", staff, null, "Chưa phù hợp", false);
        assertRejected(rejection, application, "NEW", "REJECTED", "STAFF", staff, reason, " Chưa phù hợp", false);
        assertRejected(rejection, application, "NEW", "REJECTED", "STAFF", staff, reason, "", false);
        // No other row names one.
        assertRejected(rejection, application, "NEW", "SCREENING", "STAFF", staff, reason, "Chưa phù hợp", false);
        assertRejected(rejection, application, "NEW", "WITHDRAWN", "CANDIDATE", null, reason, "Chưa phù hợp", false);
        // Every SCREENING → OFFER is the HR exception, and nothing else is.
        assertRejected(skip, application, "SCREENING", "OFFER", "STAFF", staff, null, null, false);
        assertRejected(skip, application, "INTERVIEW", "OFFER", "STAFF", staff, null, null, true);
        assertRejected(skip, application, "NEW", "SCREENING", "STAFF", staff, null, null, true);
        assertRejected(skip, application, "SCREENING", "WITHDRAWN", "CANDIDATE", null, null, null, true);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_stage_events", Integer.class)).isZero();
    }

    // A catalog value a rejection names cannot be deleted (only deactivated), and the stored name stays what it was.
    @Test
    void aReasonInUseIsKeptAndItsNameIsASnapshot() {
        var fixture = insertFixture(jdbc);
        UUID reason = insertCatalogValue("REJECTION_REASON");
        insertEvent(insertApplication(jdbc, fixture, "a@example.test", "REJECTED"), "NEW", "REJECTED", "STAFF",
                fixture.account(), reason, "Chưa phù hợp", false);
        jdbc.update("UPDATE recruitment_catalog_items SET name = 'Tên mới', active = false WHERE id = ?", reason);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM recruitment_catalog_items WHERE id = ?", reason))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("application_stage_events_rejection_reason_id_fkey");
        assertThat(jdbc.queryForObject("SELECT rejection_reason_name FROM application_stage_events", String.class))
                .isEqualTo("Chưa phù hợp");
    }

    private static void insertEvent(UUID application, String before, String after, String actorType, UUID actor,
                                    UUID reason, String reasonName, boolean skipped) {
        jdbc.update(INSERT, UUID.randomUUID(), application, before, after, actorType, actor, AT, reason, reasonName,
                skipped);
    }

    private static void assertRejected(String constraint, UUID application, String before, String after,
                                       String actorType, UUID actor, UUID reason, String reasonName, boolean skipped) {
        assertThatThrownBy(() -> insertEvent(application, before, after, actorType, actor, reason, reasonName, skipped))
                .as(constraint + " " + Arrays.asList(before, after, actorType, reason, reasonName, skipped))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }

    private static UUID insertCatalogValue(String type) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO recruitment_catalog_items (id,catalog_type,code,name,sort_order,active,created_at,"
                + "updated_at) VALUES (?,?,?,'Chưa phù hợp',0,true,?,?)", id, type, id.toString(), AT, AT);
        return id;
    }

    private record Fixture(UUID account, UUID requisition, UUID posting) { }

    // An account, a department, a position, a requisition and its draft posting: enough for applications written with
    // SQL, which only need the posting and requisition keys (the V34 migration test's fixture).
    private static Fixture insertFixture(JdbcTemplate db) {
        UUID account = UUID.randomUUID(), department = UUID.randomUUID(), position = UUID.randomUUID();
        UUID requisition = UUID.randomUUID(), posting = UUID.randomUUID();
        db.update("INSERT INTO user_accounts (id,email,password_hash,full_name,enabled,created_at) VALUES (?,?,'test-only','HR',true,?)",
                account, account + "@example.test", AT);
        db.update("INSERT INTO departments (id,code,name,manager_user_id) VALUES (?,?,?,?)", department, "IT",
                "Công nghệ thông tin", account);
        db.update("INSERT INTO positions (id,code,name,level,salary_min,salary_max,created_at,updated_at) VALUES (?,?,?,?,0,0,?,?)",
                position, "DEV", "Lập trình viên", "Junior", AT, AT);
        db.update("""
                INSERT INTO recruitment_requisitions (id,position_id,department_id,headcount,reason,status,created_by,created_at,updated_at)
                VALUES (?,?,?,1,'NEW_HEADCOUNT','DRAFT',?,?,?)
                """, requisition, position, department, account, AT, AT);
        db.update("""
                INSERT INTO job_postings (id,requisition_id,status,revision,title,job_description,candidate_requirements,
                    show_salary,created_by,created_at,updated_at)
                VALUES (?,?,'DRAFT',1,'Lập trình viên Java','Mô tả','Yêu cầu',false,?,?,?)
                """, posting, requisition, account, AT, AT);
        return new Fixture(account, requisition, posting);
    }

    private static UUID insertApplication(JdbcTemplate db, Fixture fixture, String email, String stage) {
        UUID id = UUID.randomUUID();
        byte[] hash = new byte[32];
        RANDOM.nextBytes(hash);
        db.update("""
                INSERT INTO job_applications (id,job_posting_id,requisition_id,full_name,email,normalized_email,phone,
                    stage,received_at,posting_title,tracking_code_hash)
                VALUES (?,?,?,'Nguyễn Văn A',?,?,'0912345678',?,?,'Lập trình viên Java',?)
                """, id, fixture.posting(), fixture.requisition(), email, email, stage, AT, hash);
        return id;
    }

    private static EmbeddedPostgres startPostgres() throws Exception {
        return EmbeddedPostgres.builder().setPort(0).setServerConfig("listen_addresses", "127.0.0.1").start();
    }

    private static FluentConfiguration flyway(DataSource dataSource) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration");
    }

    // The upgrade starts from the newest migration below V40, whichever task added it (V38-V39 are reserved for
    // another lane and may be missing).
    private static MigrationVersion latestVersionBefore(DataSource dataSource, MigrationVersion version) {
        return Arrays.stream(flyway(dataSource).load().info().all())
                .map(MigrationInfo::getVersion)
                .filter(candidate -> candidate.compareTo(version) < 0)
                .max(Comparator.naturalOrder())
                .orElseThrow();
    }
}
