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
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 345 (story S4-06): V34 adds application_stage_events, the stage history of an application. Sprint 4 writes only
 * the candidate's own withdrawal; the CHECKs keep a CANDIDATE row to exactly that, the unique index allows one
 * withdrawal per application, the trigger refuses every UPDATE, and deleting the application deletes its history.
 */
class ApplicationStageEventMigrationTest {
    private static final MigrationVersion EVENTS_VERSION = MigrationVersion.fromVersion("34");
    private static final Timestamp RECEIVED_AT = Timestamp.from(Instant.parse("2026-10-15T03:00:00Z"));
    private static final Timestamp WITHDRAWN_AT = Timestamp.from(Instant.parse("2026-10-16T02:00:00.123456Z"));
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String INSERT = """
            INSERT INTO application_stage_events (id, application_id, stage_before, stage_after, actor_type, actor_id,
                reason, occurred_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    // One database migrated to V34 shared by the rule tests; each test starts without data. The upgrade test starts its
    // own database because it needs the schema before V34.
    private static EmbeddedPostgres postgres;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void startMigratedDatabase() throws Exception {
        postgres = startPostgres();
        var dataSource = postgres.getPostgresDatabase();
        flyway(dataSource).target(EVENTS_VERSION).load().migrate();
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
        jdbc.update("DELETE FROM positions");
        jdbc.update("DELETE FROM departments");
        jdbc.update("DELETE FROM user_accounts");
    }

    // The upgrade adds an empty table and changes no application.
    @Test
    void theUpgradeKeepsEveryApplicationAndStartsAnEmptyHistory() throws Exception {
        try (var upgradePostgres = startPostgres()) {
            var dataSource = upgradePostgres.getPostgresDatabase();
            flyway(dataSource).target(latestVersionBefore(dataSource, EVENTS_VERSION)).load().migrate();
            var db = new JdbcTemplate(dataSource);
            var fixture = insertFixture(db);
            insertApplication(db, fixture, "a@example.test", "WITHDRAWN");
            insertApplication(db, fixture, "b@example.test", "NEW");
            var previous = db.queryForList("SELECT a::text FROM job_applications a ORDER BY id", String.class);

            var flyway = flyway(dataSource).target(EVENTS_VERSION).load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            flyway.validate();

            assertThat(db.queryForList("SELECT a::text FROM job_applications a ORDER BY id", String.class))
                    .hasSize(2).isEqualTo(previous);
            assertThat(db.queryForObject("SELECT count(*) FROM application_stage_events", Integer.class)).isZero();
        }
    }

    // What Sprint 4 writes: a candidate's withdrawal from any active stage, with or without a reason; and what Sprint 5
    // will: a staff member's stage change.
    @Test
    void aCandidateWithdrawalAndAStaffChangeAreAccepted() {
        var fixture = insertFixture(jdbc);
        for (String before : new String[] {"NEW", "SCREENING", "INTERVIEW", "OFFER"}) {
            UUID application = insertApplication(jdbc, fixture, before.toLowerCase(Locale.ROOT) + "@example.test",
                    "WITHDRAWN");
            insertEvent(application, before, "WITHDRAWN", "CANDIDATE", null, before.equals("NEW") ? null : "Lý do");
        }
        UUID staffChange = insertApplication(jdbc, fixture, "staff@example.test", "SCREENING");
        insertEvent(staffChange, "NEW", "SCREENING", "STAFF", fixture.account(), null);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_stage_events", Integer.class)).isEqualTo(5);
    }

    @Test
    void theChecksRefuseEveryOtherShape() {
        var fixture = insertFixture(jdbc);
        UUID application = insertApplication(jdbc, fixture, "a@example.test", "WITHDRAWN");

        assertRejected("application_stage_events_valid_actor", application, "NEW", "WITHDRAWN", "CANDIDATE",
                fixture.account(), null);
        assertRejected("application_stage_events_valid_actor", application, "NEW", "SCREENING", "STAFF", null, null);
        assertRejected("application_stage_events_valid_actor", application, "NEW", "SCREENING", "SYSTEM", null, null);
        assertRejected("application_stage_events_candidate_withdrawal", application, "NEW", "SCREENING", "CANDIDATE",
                null, null);
        assertRejected("application_stage_events_candidate_withdrawal", application, "HIRED", "WITHDRAWN", "CANDIDATE",
                null, null);
        assertRejected("application_stage_events_candidate_withdrawal", application, "REJECTED", "WITHDRAWN",
                "CANDIDATE", null, null);
        assertRejected("application_stage_events_valid_stages", application, "NEW", "NEW", "STAFF",
                fixture.account(), null);
        assertRejected("application_stage_events_valid_stages", application, "NEW", "INTERVIEWING", "STAFF",
                fixture.account(), null);
        assertRejected("application_stage_events_visible_reason", application, "NEW", "WITHDRAWN", "CANDIDATE", null,
                " \n\t");
        assertRejected("application_stage_events_visible_reason", application, "NEW", "WITHDRAWN", "CANDIDATE", null,
                "");
        assertThatThrownBy(() -> insertEvent(application, "NEW", "WITHDRAWN", "CANDIDATE", null, "a".repeat(501)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_stage_events", Integer.class)).isZero();
    }

    // WITHDRAWN is final: a second withdrawal of one application is refused by the unique index (23505), whoever writes
    // it; other applications are not affected.
    @Test
    void anApplicationIsWithdrawnOnce() {
        var fixture = insertFixture(jdbc);
        UUID application = insertApplication(jdbc, fixture, "a@example.test", "WITHDRAWN");
        UUID other = insertApplication(jdbc, fixture, "b@example.test", "WITHDRAWN");
        insertEvent(application, "NEW", "WITHDRAWN", "CANDIDATE", null, null);
        insertEvent(other, "OFFER", "WITHDRAWN", "CANDIDATE", null, null);

        assertThatThrownBy(() -> insertEvent(application, "SCREENING", "WITHDRAWN", "CANDIDATE", null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("application_stage_events_one_withdrawal_idx")
                .rootCause().isInstanceOfSatisfying(SQLException.class,
                        failure -> assertThat(failure.getSQLState()).isEqualTo("23505"));
        assertThatThrownBy(() -> insertEvent(application, "OFFER", "WITHDRAWN", "STAFF", fixture.account(), null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("application_stage_events_one_withdrawal_idx");
    }

    // A saved event is never changed (23514, like the other history tables), not even its reason; deleting the
    // application deletes its history (CASCADE), while the staff reference is RESTRICT.
    @Test
    void anEventIsNeverChangedAndGoesWithItsApplication() {
        var fixture = insertFixture(jdbc);
        UUID application = insertApplication(jdbc, fixture, "a@example.test", "WITHDRAWN");
        UUID staffChange = insertApplication(jdbc, fixture, "b@example.test", "SCREENING");
        // A staff account referenced by nothing but the event, so its RESTRICT is the one that refuses the delete.
        UUID staff = UUID.randomUUID();
        jdbc.update("INSERT INTO user_accounts (id,email,password_hash,full_name,enabled,created_at) VALUES (?,?,'test-only','Recruiter',true,?)",
                staff, staff + "@example.test", RECEIVED_AT);
        insertEvent(application, "NEW", "WITHDRAWN", "CANDIDATE", null, "Lý do");
        insertEvent(staffChange, "NEW", "SCREENING", "STAFF", staff, null);

        for (String update : new String[] {"UPDATE application_stage_events SET reason = 'Khác'",
                "UPDATE application_stage_events SET occurred_at = occurred_at + INTERVAL '1 second'"}) {
            assertThatThrownBy(() -> jdbc.update(update))
                    .rootCause().isInstanceOfSatisfying(SQLException.class, failure -> {
                        assertThat(failure.getSQLState()).isEqualTo("23514");
                        assertThat(failure.getMessage()).contains("application_stage_events rows are never changed");
                    });
        }
        assertThat(jdbc.queryForObject("SELECT reason FROM application_stage_events WHERE application_id = ?",
                String.class, application)).isEqualTo("Lý do");

        assertThatThrownBy(() -> jdbc.update("DELETE FROM user_accounts WHERE id = ?", staff))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("application_stage_events_actor_id_fkey");
        assertThat(jdbc.update("DELETE FROM job_applications WHERE id = ?", application)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_stage_events WHERE application_id = ?",
                Integer.class, application)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_stage_events", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> insertEvent(UUID.randomUUID(), "NEW", "WITHDRAWN", "CANDIDATE", null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("application_stage_events_application_id_fkey");
    }

    private static void insertEvent(UUID application, String before, String after, String actorType, UUID actor,
                                    String reason) {
        jdbc.update(INSERT, UUID.randomUUID(), application, before, after, actorType, actor, reason, WITHDRAWN_AT);
    }

    private static void assertRejected(String constraint, UUID application, String before, String after,
                                       String actorType, UUID actor, String reason) {
        assertThatThrownBy(() -> insertEvent(application, before, after, actorType, actor, reason))
                .as(constraint + " " + Arrays.asList(before, after, actorType, actor, reason))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }

    private record Fixture(UUID account, UUID requisition, UUID posting) { }

    // An account, a department, a position, a requisition and its draft posting: enough for applications written with
    // SQL, which only need the posting and requisition keys.
    private static Fixture insertFixture(JdbcTemplate db) {
        UUID account = UUID.randomUUID(), department = UUID.randomUUID(), position = UUID.randomUUID();
        UUID requisition = UUID.randomUUID(), posting = UUID.randomUUID();
        db.update("INSERT INTO user_accounts (id,email,password_hash,full_name,enabled,created_at) VALUES (?,?,'test-only','HR',true,?)",
                account, account + "@example.test", RECEIVED_AT);
        db.update("INSERT INTO departments (id,code,name,manager_user_id) VALUES (?,?,?,?)", department, "IT",
                "Công nghệ thông tin", account);
        db.update("INSERT INTO positions (id,code,name,level,salary_min,salary_max,created_at,updated_at) VALUES (?,?,?,?,0,0,?,?)",
                position, "DEV", "Lập trình viên", "Junior", RECEIVED_AT, RECEIVED_AT);
        db.update("""
                INSERT INTO recruitment_requisitions (id,position_id,department_id,headcount,reason,status,created_by,created_at,updated_at)
                VALUES (?,?,?,1,'NEW_HEADCOUNT','DRAFT',?,?,?)
                """, requisition, position, department, account, RECEIVED_AT, RECEIVED_AT);
        db.update("""
                INSERT INTO job_postings (id,requisition_id,status,revision,title,job_description,candidate_requirements,
                    show_salary,created_by,created_at,updated_at)
                VALUES (?,?,'DRAFT',1,'Lập trình viên Java','Mô tả','Yêu cầu',false,?,?,?)
                """, posting, requisition, account, RECEIVED_AT, RECEIVED_AT);
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
                """, id, fixture.posting(), fixture.requisition(), email, email, stage, RECEIVED_AT, hash);
        return id;
    }

    private static EmbeddedPostgres startPostgres() throws Exception {
        return EmbeddedPostgres.builder().setPort(0).setServerConfig("listen_addresses", "127.0.0.1").start();
    }

    private static FluentConfiguration flyway(DataSource dataSource) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration");
    }

    // The upgrade starts from the newest migration below V34, whichever task added it (V29-V30 are reserved).
    private static MigrationVersion latestVersionBefore(DataSource dataSource, MigrationVersion version) {
        return Arrays.stream(flyway(dataSource).load().info().all())
                .map(MigrationInfo::getVersion)
                .filter(candidate -> candidate.compareTo(version) < 0)
                .max(Comparator.naturalOrder())
                .orElseThrow();
    }
}
