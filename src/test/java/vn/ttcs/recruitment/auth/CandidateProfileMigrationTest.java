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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 360 (story S4-09): V32 adds one candidate profile per normalized email (candidates) and the link of every
 * application to its profile (candidate_applications), and links the applications stored before it ran.
 */
class CandidateProfileMigrationTest {
    private static final MigrationVersion PROFILE_VERSION = MigrationVersion.fromVersion("32");
    private static final Timestamp CREATED_AT = Timestamp.from(Instant.parse("2026-10-01T00:00:00Z"));
    private static final Timestamp EARLIEST = Timestamp.from(Instant.parse("2026-10-10T01:00:00.123456Z"));
    private static final Timestamp LATER = Timestamp.from(Instant.parse("2026-10-12T02:30:00.654321Z"));
    private static final Timestamp LATEST = Timestamp.from(Instant.parse("2026-10-14T08:15:00.987654Z"));
    private static final SecureRandom RANDOM = new SecureRandom();

    // One database migrated to V32 shared by the rule tests; each test starts without data. The target stays V32, so
    // these tests keep checking V32 itself when later migrations change the tables. The upgrade test starts its own
    // database because it needs the schema before V32.
    private static EmbeddedPostgres postgres;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void startMigratedDatabase() throws Exception {
        postgres = startPostgres();
        var dataSource = postgres.getPostgresDatabase();
        flyway(dataSource).target(PROFILE_VERSION).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        postgres.close();
    }

    @BeforeEach
    void deleteData() {
        jdbc.update("DELETE FROM candidate_applications");
        jdbc.update("DELETE FROM candidates");
        jdbc.update("DELETE FROM application_cvs");
        jdbc.update("DELETE FROM job_applications");
        jdbc.update("DELETE FROM job_postings");
        jdbc.update("DELETE FROM recruitment_requisitions");
        jdbc.update("DELETE FROM positions");
        jdbc.update("DELETE FROM departments");
        jdbc.update("DELETE FROM user_accounts");
    }

    // The fixture of the review: two applications in two requisitions whose raw emails differ ("A@Example.TEST" and
    // the lower-case address between two no-break spaces) but whose normalized email is the same, and one application
    // of another person. The upgrade groups by the normalized email, never by the raw one.
    @Test
    void theUpgradeMakesOneProfilePerNormalizedEmailAndLinksEveryApplication() throws Exception {
        try (var upgradePostgres = startPostgres()) {
            var dataSource = upgradePostgres.getPostgresDatabase();
            flyway(dataSource).target(latestVersionBefore(dataSource, PROFILE_VERSION)).load().migrate();
            var db = new JdbcTemplate(dataSource);
            var fixture = insertFixture(db);
            String nbsp = Character.toString(0x00A0);
            UUID upperCase = insertApplication(db, fixture.first(), "A@Example.TEST", "a@example.test", LATER);
            UUID padded = insertApplication(db, fixture.second(), nbsp + "a@example.test" + nbsp, "a@example.test",
                    EARLIEST);
            UUID other = insertApplication(db, fixture.first(), "b@example.test", "b@example.test", LATEST);
            // Whole rows as text, so the tracking hash (BYTEA) is compared by value.
            var previousApplications = db.queryForList("SELECT a::text FROM job_applications a ORDER BY id", String.class);

            var flyway = flyway(dataSource).target(PROFILE_VERSION).load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            flyway.validate();

            assertThat(db.queryForList("SELECT a::text FROM job_applications a ORDER BY id", String.class))
                    .hasSize(3).isEqualTo(previousApplications);
            assertThat(db.queryForList("SELECT normalized_email FROM candidates ORDER BY normalized_email", String.class))
                    .containsExactly("a@example.test", "b@example.test")
                    .containsExactlyElementsOf(db.queryForList(
                            "SELECT DISTINCT normalized_email FROM job_applications ORDER BY normalized_email",
                            String.class));
            assertThat(db.queryForObject("SELECT count(*) FROM candidate_applications", Integer.class)).isEqualTo(3);
            UUID person = candidateOf(db, upperCase);
            assertThat(candidateOf(db, padded)).isEqualTo(person);
            assertThat(candidateOf(db, other)).isNotEqualTo(person);
            // A profile is created at its person's first application; every link at its own application's receipt.
            assertThat(db.queryForObject("SELECT created_at FROM candidates WHERE id = ?", Timestamp.class, person))
                    .isEqualTo(EARLIEST);
            assertThat(db.queryForObject("SELECT created_at FROM candidates WHERE id = ?", Timestamp.class,
                    candidateOf(db, other))).isEqualTo(LATEST);
            assertThat(db.queryForObject("""
                    SELECT count(*) FROM candidate_applications l JOIN job_applications a ON a.id = l.application_id
                    WHERE l.linked_at = a.received_at
                    """, Integer.class)).isEqualTo(3);
        }
    }

    @Test
    void anEmailHasOneProfileAndNoBlankOrPaddedValue() {
        insertCandidate("a@example.test");
        assertRejected("INSERT INTO candidates (id, normalized_email, created_at) VALUES (?, ?, ?)",
                "candidates_normalized_email_key", UUID.randomUUID(), "a@example.test", CREATED_AT);
        assertRejected("INSERT INTO candidates (id, normalized_email, created_at) VALUES (?, ?, ?)",
                "candidates_valid_email", UUID.randomUUID(), " c@example.test", CREATED_AT);
        assertRejected("INSERT INTO candidates (id, normalized_email, created_at) VALUES (?, ?, ?)",
                "candidates_valid_email", UUID.randomUUID(), "", CREATED_AT);
        // Letter case is the Java Locale.ROOT rule (V26), not the database's: the table takes what Java normalized.
        insertCandidate("A@example.test");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidates", Integer.class)).isEqualTo(2);
    }

    @Test
    void aLinkedProfileStaysAndADeletedApplicationTakesItsLinkWithIt() {
        var fixture = insertFixture(jdbc);
        UUID application = insertApplication(jdbc, fixture.first(), "a@example.test", "a@example.test", LATER);
        UUID candidate = insertCandidate("a@example.test");
        UUID another = insertCandidate("b@example.test");
        link(application, candidate);

        // One profile per application, an existing profile, an existing application.
        assertRejected("INSERT INTO candidate_applications (application_id, candidate_id, linked_at) VALUES (?, ?, ?)",
                "candidate_applications_pkey", application, another, LATER);
        assertRejected("INSERT INTO candidate_applications (application_id, candidate_id, linked_at) VALUES (?, ?, ?)",
                "candidate_applications_candidate_id_fkey", insertApplication(jdbc, fixture.second(), "c@example.test",
                        "c@example.test", LATER), UUID.randomUUID(), LATER);
        assertRejected("INSERT INTO candidate_applications (application_id, candidate_id, linked_at) VALUES (?, ?, ?)",
                "candidate_applications_application_id_fkey", UUID.randomUUID(), another, LATER);

        // RESTRICT: a profile with applications never disappears silently (SQLSTATE 23503).
        assertThatThrownBy(() -> jdbc.update("DELETE FROM candidates WHERE id = ?", candidate))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("candidate_applications_candidate_id_fkey")
                .rootCause().isInstanceOfSatisfying(SQLException.class,
                        failure -> assertThat(failure.getSQLState()).isEqualTo("23503"));

        // CASCADE: deleting the application removes its link and keeps the profile, which can then be deleted.
        jdbc.update("DELETE FROM job_applications WHERE id = ?", application);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_applications WHERE candidate_id = ?",
                Integer.class, candidate)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidates WHERE id = ?", Integer.class, candidate))
                .isEqualTo(1);
        assertThat(jdbc.update("DELETE FROM candidates WHERE id = ?", candidate)).isEqualTo(1);
    }

    private static UUID insertCandidate(String normalizedEmail) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO candidates (id, normalized_email, created_at) VALUES (?, ?, ?)", id, normalizedEmail,
                CREATED_AT);
        return id;
    }

    private static void link(UUID application, UUID candidate) {
        jdbc.update("INSERT INTO candidate_applications (application_id, candidate_id, linked_at) VALUES (?, ?, ?)",
                application, candidate, LATER);
    }

    private static UUID candidateOf(JdbcTemplate db, UUID application) {
        return db.queryForObject("SELECT candidate_id FROM candidate_applications WHERE application_id = ?", UUID.class,
                application);
    }

    private record Posting(UUID requisition, UUID posting) { }

    private record Fixture(Posting first, Posting second) { }

    // An account, a department, a position and two requisitions, each with its draft posting (one posting per
    // requisition, V21): enough for applications written with SQL, which only need the posting and requisition keys.
    private static Fixture insertFixture(JdbcTemplate db) {
        UUID account = UUID.randomUUID(), department = UUID.randomUUID(), position = UUID.randomUUID();
        db.update("INSERT INTO user_accounts (id,email,password_hash,full_name,enabled,created_at) VALUES (?,?,'test-only','HR',true,?)",
                account, account + "@example.test", CREATED_AT);
        db.update("INSERT INTO departments (id,code,name,manager_user_id) VALUES (?,?,?,?)", department, "IT",
                "Công nghệ thông tin", account);
        db.update("INSERT INTO positions (id,code,name,level,salary_min,salary_max,created_at,updated_at) VALUES (?,?,?,?,0,0,?,?)",
                position, "DEV", "Lập trình viên", "Junior", CREATED_AT, CREATED_AT);
        return new Fixture(insertPosting(db, account, department, position),
                insertPosting(db, account, department, position));
    }

    private static Posting insertPosting(JdbcTemplate db, UUID account, UUID department, UUID position) {
        UUID requisition = UUID.randomUUID(), posting = UUID.randomUUID();
        db.update("""
                INSERT INTO recruitment_requisitions (id,position_id,department_id,headcount,reason,status,created_by,created_at,updated_at)
                VALUES (?,?,?,1,'NEW_HEADCOUNT','DRAFT',?,?,?)
                """, requisition, position, department, account, CREATED_AT, CREATED_AT);
        db.update("""
                INSERT INTO job_postings (id,requisition_id,status,revision,title,job_description,candidate_requirements,
                    show_salary,created_by,created_at,updated_at)
                VALUES (?,?,'DRAFT',1,'Lập trình viên Java','Mô tả','Yêu cầu',false,?,?,?)
                """, posting, requisition, account, CREATED_AT, CREATED_AT);
        return new Posting(requisition, posting);
    }

    private static UUID insertApplication(JdbcTemplate db, Posting posting, String email, String normalizedEmail,
                                          Timestamp receivedAt) {
        UUID id = UUID.randomUUID();
        byte[] hash = new byte[32];
        RANDOM.nextBytes(hash);
        db.update("""
                INSERT INTO job_applications (id,job_posting_id,requisition_id,full_name,email,normalized_email,phone,
                    received_at,posting_title,tracking_code_hash)
                VALUES (?,?,?,'Nguyễn Văn A',?,?,'0912345678',?,'Lập trình viên Java',?)
                """, id, posting.posting(), posting.requisition(), email, normalizedEmail, receivedAt, hash);
        return id;
    }

    private static void assertRejected(String sql, String constraint, Object... arguments) {
        assertThatThrownBy(() -> jdbc.update(sql, arguments))
                .as(constraint + " " + Arrays.toString(arguments))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }

    private static EmbeddedPostgres startPostgres() throws Exception {
        return EmbeddedPostgres.builder().setPort(0).setServerConfig("listen_addresses", "127.0.0.1").start();
    }

    private static FluentConfiguration flyway(DataSource dataSource) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration");
    }

    // The upgrade starts from the newest migration below V32, whichever task added it (V29-V30 are reserved).
    private static MigrationVersion latestVersionBefore(DataSource dataSource, MigrationVersion version) {
        return Arrays.stream(flyway(dataSource).load().info().all())
                .map(MigrationInfo::getVersion)
                .filter(candidate -> candidate.compareTo(version) < 0)
                .max(Comparator.naturalOrder())
                .orElseThrow();
    }
}
