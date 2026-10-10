package vn.ttcs.recruitment.auth;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Task 283: V16 adds requisition_recruiters, the recruiters currently assigned to each requisition.
class RequisitionRecruiterMigrationTest {
    private static final Timestamp CREATED_AT = Timestamp.from(Instant.parse("2026-10-10T00:00:00Z"));
    private static final Timestamp ASSIGNED_AT = Timestamp.from(Instant.parse("2026-10-10T02:15:30.123456Z"));
    private static final MigrationVersion RECRUITER_VERSION = MigrationVersion.fromVersion("16");

    @Test
    void upgradesWithoutChangingRequisitionsOrPermissions() throws Exception {
        try (var postgres = startPostgres()) {
            var dataSource = postgres.getPostgresDatabase();
            flyway(dataSource).target(latestVersionBefore(dataSource, RECRUITER_VERSION)).load().migrate();
            var jdbc = new JdbcTemplate(dataSource);
            insertFixture(jdbc);
            var previousRequisitions = jdbc.queryForList("SELECT * FROM recruitment_requisitions ORDER BY id");
            var previousPermissions = jdbc.queryForList("SELECT * FROM permissions ORDER BY code");
            var previousGrants = jdbc.queryForList("SELECT * FROM role_permissions ORDER BY role_code,permission_code");

            var flyway = flyway(dataSource).target(RECRUITER_VERSION).load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            flyway.validate();

            assertThat(jdbc.queryForList("SELECT * FROM recruitment_requisitions ORDER BY id"))
                    .isEqualTo(previousRequisitions);
            assertThat(jdbc.queryForList("SELECT * FROM permissions ORDER BY code")).isEqualTo(previousPermissions);
            assertThat(jdbc.queryForList("SELECT * FROM role_permissions ORDER BY role_code,permission_code"))
                    .isEqualTo(previousGrants);
            // Existing requisitions start unassigned.
            assertThat(count(jdbc)).isZero();
        }
    }

    @Test
    void storesOnePrimaryAndSeveralSupportingRecruiters() throws Exception {
        try (var postgres = startPostgres()) {
            var jdbc = migrateAll(postgres);
            var fixture = insertFixture(jdbc);
            insert(jdbc, fixture.requisitionId(), fixture.recruiters()[0], "PRIMARY", fixture.hrId());
            insert(jdbc, fixture.requisitionId(), fixture.recruiters()[1], "SUPPORTING", fixture.hrId());
            insert(jdbc, fixture.requisitionId(), fixture.recruiters()[2], "SUPPORTING", fixture.hrId());

            Map<String, Object> primary = jdbc.queryForMap(
                    "SELECT * FROM requisition_recruiters WHERE assignment_role = 'PRIMARY'");
            assertThat(primary.get("requisition_id")).isEqualTo(fixture.requisitionId());
            assertThat(primary.get("recruiter_id")).isEqualTo(fixture.recruiters()[0]);
            assertThat(primary.get("assigned_by")).isEqualTo(fixture.hrId());
            assertThat(primary.get("assigned_at")).isEqualTo(ASSIGNED_AT);
            assertThat(count(jdbc)).isEqualTo(3);
        }
    }

    @Test
    void refusesASecondPrimaryButAllowsTheSamePrimaryOnAnotherRequisition() throws Exception {
        try (var postgres = startPostgres()) {
            var jdbc = migrateAll(postgres);
            var fixture = insertFixture(jdbc);
            insert(jdbc, fixture.requisitionId(), fixture.recruiters()[0], "PRIMARY", fixture.hrId());

            assertViolation(() -> insert(jdbc, fixture.requisitionId(), fixture.recruiters()[1], "PRIMARY",
                    fixture.hrId()), "requisition_recruiters_one_primary_idx");
            UUID other = insertRequisition(jdbc, fixture);
            insert(jdbc, other, fixture.recruiters()[0], "PRIMARY", fixture.hrId());
            assertThat(count(jdbc)).isEqualTo(2);
        }
    }

    @Test
    void refusesTheSameRecruiterTwiceOnARequisitionAndUnknownRoles() throws Exception {
        try (var postgres = startPostgres()) {
            var jdbc = migrateAll(postgres);
            var fixture = insertFixture(jdbc);
            insert(jdbc, fixture.requisitionId(), fixture.recruiters()[0], "PRIMARY", fixture.hrId());
            insert(jdbc, fixture.requisitionId(), fixture.recruiters()[1], "SUPPORTING", fixture.hrId());

            // Never primary and supporting at the same time, never supporting twice.
            assertViolation(() -> insert(jdbc, fixture.requisitionId(), fixture.recruiters()[0], "SUPPORTING",
                    fixture.hrId()), "requisition_recruiters_pkey");
            assertViolation(() -> insert(jdbc, fixture.requisitionId(), fixture.recruiters()[1], "SUPPORTING",
                    fixture.hrId()), "requisition_recruiters_pkey");
            assertViolation(() -> insert(jdbc, fixture.requisitionId(), fixture.recruiters()[2], "LEAD",
                    fixture.hrId()), "valid_requisition_recruiter_role");
            assertViolation(() -> insert(jdbc, fixture.requisitionId(), fixture.recruiters()[2], "primary",
                    fixture.hrId()), "valid_requisition_recruiter_role");
            assertThat(count(jdbc)).isEqualTo(2);
        }
    }

    @Test
    void keepsAssignmentsWhenTheRequisitionOrAccountsAreDeleted() throws Exception {
        try (var postgres = startPostgres()) {
            var jdbc = migrateAll(postgres);
            var fixture = insertFixture(jdbc);
            insert(jdbc, fixture.requisitionId(), fixture.recruiters()[0], "PRIMARY", fixture.hrId());

            assertViolation(() -> jdbc.update("DELETE FROM recruitment_requisitions"),
                    "requisition_recruiters_requisition_id_fkey");
            assertViolation(() -> jdbc.update("DELETE FROM user_accounts WHERE id = ?", fixture.recruiters()[0]),
                    "requisition_recruiters_recruiter_id_fkey");
            assertViolation(() -> jdbc.update("DELETE FROM user_accounts WHERE id = ?", fixture.hrId()),
                    "requisition_recruiters_assigned_by_fkey");
            assertViolation(() -> insert(jdbc, UUID.randomUUID(), fixture.recruiters()[1], "PRIMARY", fixture.hrId()),
                    "requisition_recruiters_requisition_id_fkey");
            assertThat(count(jdbc)).isOne();
        }
    }

    private static int count(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT count(*) FROM requisition_recruiters", Integer.class);
    }

    private static void insert(JdbcTemplate jdbc, UUID requisitionId, UUID recruiterId, String role, UUID by) {
        jdbc.update("""
                INSERT INTO requisition_recruiters (requisition_id, recruiter_id, assignment_role, assigned_by,
                    assigned_at)
                VALUES (?, ?, ?, ?, ?)
                """, requisitionId, recruiterId, role, by, ASSIGNED_AT);
    }

    private static void assertViolation(Runnable statement, String constraint) {
        assertThatThrownBy(statement::run).as(constraint)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }

    private static EmbeddedPostgres startPostgres() throws Exception {
        return EmbeddedPostgres.builder().setPort(0)
                .setServerConfig("listen_addresses", "127.0.0.1").start();
    }

    private static FluentConfiguration flyway(DataSource dataSource) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration");
    }

    // The upgrade starts from the newest migration below V16, whichever task added it.
    private static MigrationVersion latestVersionBefore(DataSource dataSource, MigrationVersion version) {
        return Arrays.stream(flyway(dataSource).load().info().all())
                .map(MigrationInfo::getVersion)
                .filter(candidate -> candidate.compareTo(version) < 0)
                .max(Comparator.naturalOrder())
                .orElseThrow();
    }

    private static JdbcTemplate migrateAll(EmbeddedPostgres postgres) {
        var dataSource = postgres.getPostgresDatabase();
        flyway(dataSource).load().migrate();
        return new JdbcTemplate(dataSource);
    }

    // An HR manager, the head of IT, three recruiters, one position and one draft of IT.
    private static Fixture insertFixture(JdbcTemplate jdbc) {
        UUID hrId = insertAccount(jdbc, "hr@example.test", "HR_MANAGER");
        UUID headId = insertAccount(jdbc, "head@example.test", "HIRING_MANAGER");
        UUID[] recruiters = new UUID[3];
        for (int i = 0; i < recruiters.length; i++) {
            recruiters[i] = insertAccount(jdbc, "recruiter" + i + "@example.test", "RECRUITER");
        }
        UUID departmentId = UUID.randomUUID();
        jdbc.update("INSERT INTO departments (id,code,name,manager_user_id) VALUES (?,?,?,?)",
                departmentId, "IT", "Công nghệ thông tin", headId);
        UUID positionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO positions (id,code,name,level,salary_min,salary_max,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, positionId, "DEV", "Lập trình viên", "Junior", 15_000_000L, 25_000_000L, CREATED_AT, CREATED_AT);
        var fixture = new Fixture(hrId, headId, departmentId, positionId, recruiters, null);
        return new Fixture(hrId, headId, departmentId, positionId, recruiters, insertRequisition(jdbc, fixture));
    }

    private static UUID insertRequisition(JdbcTemplate jdbc, Fixture fixture) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO recruitment_requisitions (id,position_id,department_id,headcount,reason,created_by,
                    created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, id, fixture.positionId(), fixture.departmentId(), 1, "NEW_HEADCOUNT", fixture.headId(),
                CREATED_AT, CREATED_AT);
        return id;
    }

    private static UUID insertAccount(JdbcTemplate jdbc, String email, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO user_accounts (id,email,full_name,password_hash,created_at) VALUES (?,?,?,?,?)",
                id, email, "Tài khoản kiểm thử", "unchanged-password-hash", CREATED_AT);
        jdbc.update("INSERT INTO user_roles (user_id,role) VALUES (?,?)", id, role);
        return id;
    }

    private record Fixture(UUID hrId, UUID headId, UUID departmentId, UUID positionId, UUID[] recruiters,
                           UUID requisitionId) { }
}
