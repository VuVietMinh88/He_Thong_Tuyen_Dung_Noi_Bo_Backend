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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

// Task 286: V17 adds requisition_recruiter_changes, the append-only history of recruiter changes.
class RequisitionRecruiterHistoryMigrationTest {
    private static final Timestamp CREATED_AT = Timestamp.from(Instant.parse("2026-10-10T00:00:00Z"));
    private static final Timestamp AT_1 = Timestamp.from(Instant.parse("2026-10-10T02:00:00Z"));
    private static final Timestamp AT_2 = Timestamp.from(Instant.parse("2026-10-10T02:30:00Z"));
    private static final MigrationVersion HISTORY_VERSION = MigrationVersion.fromVersion("17");

    @Test
    void theUpgradeRecordsOneRowPerCurrentAssignmentPrimaryFirst() throws Exception {
        try (var postgres = startPostgres()) {
            var dataSource = postgres.getPostgresDatabase();
            flyway(dataSource).target(latestVersionBefore(dataSource, HISTORY_VERSION)).load().migrate();
            var jdbc = new JdbcTemplate(dataSource);
            var fixture = insertFixture(jdbc);
            UUID other = insertRequisition(jdbc, fixture);
            // Saved by task 284 before V17: a primary and two supporting recruiters (one added before the primary's
            // last handover), and another requisition with only a primary.
            assign(jdbc, fixture.requisitionId(), fixture.recruiters()[0], "PRIMARY", AT_2, fixture.hrId());
            assign(jdbc, fixture.requisitionId(), fixture.recruiters()[1], "SUPPORTING", AT_1, fixture.hrId());
            assign(jdbc, fixture.requisitionId(), fixture.recruiters()[2], "SUPPORTING", AT_2, fixture.hrId());
            assign(jdbc, other, fixture.recruiters()[1], "PRIMARY", AT_1, fixture.hrId());
            var previousAssignments = jdbc.queryForList("SELECT * FROM requisition_recruiters ORDER BY recruiter_id");
            var previousPermissions = jdbc.queryForList("SELECT * FROM role_permissions ORDER BY role_code,permission_code");

            var flyway = flyway(dataSource).target(HISTORY_VERSION).load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            flyway.validate();

            assertThat(jdbc.queryForList("SELECT * FROM requisition_recruiters ORDER BY recruiter_id"))
                    .isEqualTo(previousAssignments);
            assertThat(jdbc.queryForList("SELECT * FROM role_permissions ORDER BY role_code,permission_code"))
                    .isEqualTo(previousPermissions);
            List<Map<String, Object>> history = jdbc.queryForList("""
                    SELECT revision, change_type, recruiter_id, previous_recruiter_id, changed_by, changed_at, note
                    FROM requisition_recruiter_changes WHERE requisition_id = ? ORDER BY revision
                    """, fixture.requisitionId());
            assertThat(history).extracting(row -> row.get("revision"), row -> row.get("change_type"),
                    row -> row.get("recruiter_id"), row -> row.get("changed_at")).containsExactly(
                    tuple(1, "PRIMARY_ASSIGNED", fixture.recruiters()[0], AT_2),
                    tuple(2, "SUPPORTING_ADDED", fixture.recruiters()[1], AT_1),
                    tuple(3, "SUPPORTING_ADDED", fixture.recruiters()[2], AT_2));
            assertThat(history).allSatisfy(row -> {
                assertThat(row.get("previous_recruiter_id")).isNull();
                assertThat(row.get("changed_by")).isEqualTo(fixture.hrId());
                assertThat(row.get("note")).isNull();
            });
            assertThat(jdbc.queryForObject("SELECT count(*) FROM requisition_recruiter_changes WHERE requisition_id = ?",
                    Integer.class, other)).isOne();
        }
    }

    @Test
    void aHandoverNamesThePreviousPrimaryAndNothingElseDoes() throws Exception {
        try (var postgres = startPostgres()) {
            var jdbc = migrateAll(postgres);
            var fixture = insertFixture(jdbc);
            UUID[] r = fixture.recruiters();
            insert(jdbc, row(fixture, 1, "PRIMARY_ASSIGNED", r[0], null, null));
            insert(jdbc, row(fixture, 2, "PRIMARY_HANDED_OVER", r[1], r[0], "Nghỉ phép\ndài ngày"));
            insert(jdbc, row(fixture, 3, "SUPPORTING_ADDED", r[0], null, null));

            assertViolation(jdbc, row(fixture, 4, "PRIMARY_HANDED_OVER", r[2], null, null),
                    "valid_requisition_recruiter_change_previous");
            assertViolation(jdbc, row(fixture, 4, "SUPPORTING_REMOVED", r[0], r[1], null),
                    "valid_requisition_recruiter_change_previous");
            assertViolation(jdbc, row(fixture, 4, "PRIMARY_HANDED_OVER", r[1], r[1], null),
                    "valid_requisition_recruiter_change_previous");
            assertThat(jdbc.queryForObject("SELECT note FROM requisition_recruiter_changes WHERE revision = 2",
                    String.class)).isEqualTo("Nghỉ phép\ndài ngày");
        }
    }

    @Test
    void revisionsArePositiveAndUniquePerRequisitionAndTypesAndNotesAreChecked() throws Exception {
        try (var postgres = startPostgres()) {
            var jdbc = migrateAll(postgres);
            var fixture = insertFixture(jdbc);
            UUID[] r = fixture.recruiters();
            insert(jdbc, row(fixture, 1, "PRIMARY_ASSIGNED", r[0], null, null));

            assertViolation(jdbc, row(fixture, 1, "SUPPORTING_ADDED", r[1], null, null),
                    "requisition_recruiter_changes_revision_key");
            assertViolation(jdbc, row(fixture, 0, "SUPPORTING_ADDED", r[1], null, null),
                    "positive_requisition_recruiter_change_revision");
            assertViolation(jdbc, row(fixture, 2, "SUPPORTING_MOVED", r[1], null, null),
                    "valid_requisition_recruiter_change_type");
            assertViolation(jdbc, row(fixture, 2, "SUPPORTING_ADDED", r[1], null, " \n\t "),
                    "valid_requisition_recruiter_change_note");
            // The same revision on another requisition is fine.
            UUID other = insertRequisition(jdbc, fixture);
            var otherRow = row(fixture, 1, "PRIMARY_ASSIGNED", r[0], null, null);
            otherRow.put("requisition_id", other);
            insert(jdbc, otherRow);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM requisition_recruiter_changes", Integer.class))
                    .isEqualTo(2);
        }
    }

    @Test
    void theHistoryIsKeptWhenTheRequisitionOrAccountsAreDeleted() throws Exception {
        try (var postgres = startPostgres()) {
            var jdbc = migrateAll(postgres);
            var fixture = insertFixture(jdbc);
            UUID[] r = fixture.recruiters();
            insert(jdbc, row(fixture, 1, "PRIMARY_ASSIGNED", r[0], null, null));
            insert(jdbc, row(fixture, 2, "PRIMARY_HANDED_OVER", r[1], r[0], null));

            assertDeleteRefused(jdbc, "DELETE FROM recruitment_requisitions",
                    "requisition_recruiter_changes_requisition_id_fkey");
            assertDeleteRefused(jdbc, "DELETE FROM user_accounts WHERE id = '" + r[1] + "'",
                    "requisition_recruiter_changes_recruiter_id_fkey");
            assertDeleteRefused(jdbc, "DELETE FROM user_accounts WHERE id = '" + fixture.hrId() + "'",
                    "requisition_recruiter_changes_changed_by_fkey");
            // r[0] is only the previous primary of revision 2 once revision 1 is gone.
            jdbc.update("DELETE FROM requisition_recruiter_changes WHERE revision = 1");
            assertDeleteRefused(jdbc, "DELETE FROM user_accounts WHERE id = '" + r[0] + "'",
                    "requisition_recruiter_changes_previous_recruiter_id_fkey");
        }
    }

    private static Map<String, Object> row(Fixture fixture, int revision, String type, UUID recruiter, UUID previous,
                                           String note) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", UUID.randomUUID());
        row.put("requisition_id", fixture.requisitionId());
        row.put("revision", revision);
        row.put("change_type", type);
        row.put("recruiter_id", recruiter);
        row.put("previous_recruiter_id", previous);
        row.put("changed_by", fixture.hrId());
        row.put("changed_at", AT_1);
        row.put("note", note);
        return row;
    }

    private static void insert(JdbcTemplate jdbc, Map<String, Object> row) {
        jdbc.update("INSERT INTO requisition_recruiter_changes (" + String.join(",", row.keySet()) + ") VALUES ("
                + String.join(",", row.keySet().stream().map(key -> key.equals("previous_recruiter_id")
                        ? "CAST(? AS uuid)" : "?").toList()) + ")", row.values().toArray());
    }

    private static void assertViolation(JdbcTemplate jdbc, Map<String, Object> row, String constraint) {
        assertThatThrownBy(() -> insert(jdbc, row)).as(constraint)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }

    private static void assertDeleteRefused(JdbcTemplate jdbc, String sql, String constraint) {
        assertThatThrownBy(() -> jdbc.update(sql)).as(constraint)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }

    private static void assign(JdbcTemplate jdbc, UUID requisition, UUID recruiter, String role, Timestamp at, UUID by) {
        jdbc.update("""
                INSERT INTO requisition_recruiters (requisition_id, recruiter_id, assignment_role, assigned_by,
                    assigned_at)
                VALUES (?, ?, ?, ?, ?)
                """, requisition, recruiter, role, by, at);
    }

    private static EmbeddedPostgres startPostgres() throws Exception {
        return EmbeddedPostgres.builder().setPort(0)
                .setServerConfig("listen_addresses", "127.0.0.1").start();
    }

    private static FluentConfiguration flyway(DataSource dataSource) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration");
    }

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

    // An HR manager (only referenced as changed_by), the head of IT, three recruiters and one draft of IT.
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
