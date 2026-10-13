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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Task 275: V15 adds requisition_headcount_overrides, the history of HR exceptions to the headcount plan.
class HeadcountOverrideMigrationTest {
    private static final Timestamp CREATED_AT = Timestamp.from(Instant.parse("2026-10-10T00:00:00Z"));
    private static final MigrationVersion OVERRIDE_VERSION = MigrationVersion.fromVersion("15");

    @Test
    void upgradesWithoutChangingPlansRequisitionsOrPermissions() throws Exception {
        try (var postgres = startPostgres()) {
            var dataSource = postgres.getPostgresDatabase();
            flyway(dataSource).target(latestVersionBefore(dataSource, OVERRIDE_VERSION)).load().migrate();
            var jdbc = new JdbcTemplate(dataSource);
            insertFixture(jdbc);
            jdbc.update("""
                    INSERT INTO headcount_plans (id,department_id,plan_year,headcount_limit,salary_budget,created_at,
                        updated_at,updated_by)
                    SELECT ?, id, 2026, 1, NULL, ?, ?, manager_user_id FROM departments
                    """, UUID.randomUUID(), CREATED_AT, CREATED_AT);
            var previousPlans = jdbc.queryForList("SELECT * FROM headcount_plans ORDER BY id");
            var previousRequisitions = jdbc.queryForList("SELECT * FROM recruitment_requisitions ORDER BY id");
            var previousPermissions = jdbc.queryForList("SELECT * FROM permissions ORDER BY code");
            var previousGrants = jdbc.queryForList("SELECT * FROM role_permissions ORDER BY role_code,permission_code");

            var flyway = flyway(dataSource).target(OVERRIDE_VERSION).load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            flyway.validate();

            assertThat(jdbc.queryForList("SELECT * FROM headcount_plans ORDER BY id")).isEqualTo(previousPlans);
            assertThat(jdbc.queryForList("SELECT * FROM recruitment_requisitions ORDER BY id"))
                    .isEqualTo(previousRequisitions);
            assertThat(jdbc.queryForList("SELECT * FROM permissions ORDER BY code")).isEqualTo(previousPermissions);
            assertThat(jdbc.queryForList("SELECT * FROM role_permissions ORDER BY role_code,permission_code"))
                    .isEqualTo(previousGrants);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM requisition_headcount_overrides", Integer.class))
                    .isZero();
        }
    }

    @Test
    void storesAnExceptionWithTheNumbersItWasConfirmedAgainst() throws Exception {
        try (var postgres = startPostgres()) {
            var jdbc = migrateAll(postgres);
            var fixture = insertFixture(jdbc);
            UUID id = insert(jdbc, row(fixture));

            Map<String, Object> stored = jdbc.queryForMap("SELECT * FROM requisition_headcount_overrides WHERE id=?", id);
            assertThat(stored.get("requisition_id")).isEqualTo(fixture.requisitionId());
            assertThat(stored.get("department_id")).isEqualTo(fixture.departmentId());
            assertThat(stored.get("plan_year")).isEqualTo(2026);
            assertThat(stored.get("headcount_limit")).isEqualTo(3);
            assertThat(stored.get("salary_budget")).isNull();
            assertThat(stored.get("headcount_used")).isEqualTo(3L);
            assertThat(stored.get("requested_headcount")).isEqualTo(2);
            assertThat(stored.get("requested_salary_cost")).isEqualTo(600_000_000L);
            assertThat(stored.get("headcount_exceeded")).isEqualTo(true);
            assertThat(stored.get("salary_budget_exceeded")).isEqualTo(false);
            assertThat(stored.get("reason")).isEqualTo("Dự án mới cần thêm người.");
            assertThat(stored.get("overridden_by")).isEqualTo(fixture.hrId());
            assertThat(stored.get("overridden_at")).isEqualTo(CREATED_AT);
            // A requisition may be confirmed several times.
            insert(jdbc, row(fixture));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM requisition_headcount_overrides", Integer.class))
                    .isEqualTo(2);
        }
    }

    @Test
    void rejectsBlankReasonsRowsWithoutExcessAndImpossibleNumbers() throws Exception {
        try (var postgres = startPostgres()) {
            var jdbc = migrateAll(postgres);
            var fixture = insertFixture(jdbc);

            assertViolation(jdbc, row(fixture, "reason", " \n\t "), "valid_headcount_override_reason");
            assertViolation(jdbc, row(fixture, "headcount_exceeded", false), "valid_headcount_override_excess");
            assertViolation(jdbc, row(fixture, "plan_year", 1999), "valid_headcount_override_year");
            assertViolation(jdbc, row(fixture, "headcount_limit", -1), "valid_headcount_override_numbers");
            assertViolation(jdbc, row(fixture, "salary_budget", -1L), "valid_headcount_override_numbers");
            assertViolation(jdbc, row(fixture, "headcount_used", -1L), "valid_headcount_override_numbers");
            assertViolation(jdbc, row(fixture, "requested_headcount", 0), "valid_headcount_override_numbers");
            assertViolation(jdbc, row(fixture, "requested_salary_cost", -1L), "valid_headcount_override_numbers");
            assertViolation(jdbc, row(fixture, "requisition_id", UUID.randomUUID()),
                    "requisition_headcount_overrides_requisition_id_fkey");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM requisition_headcount_overrides", Integer.class))
                    .isZero();
        }
    }

    @Test
    void theHistoryKeepsItsRequisitionDepartmentAndAccount() throws Exception {
        try (var postgres = startPostgres()) {
            var jdbc = migrateAll(postgres);
            var fixture = insertFixture(jdbc);
            insert(jdbc, row(fixture));

            assertThatThrownBy(() -> jdbc.update("DELETE FROM recruitment_requisitions"))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("requisition_headcount_overrides_requisition_id_fkey");
            assertThatThrownBy(() -> jdbc.update("DELETE FROM user_accounts WHERE id=?", fixture.hrId()))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("requisition_headcount_overrides_overridden_by_fkey");
            // Even when the requisition has moved to another department, the department of the exception stays.
            UUID other = UUID.randomUUID();
            jdbc.update("INSERT INTO departments (id,code,name,manager_user_id) VALUES (?,?,?,?)",
                    other, "SALES", "Kinh doanh", fixture.hrId());
            jdbc.update("UPDATE recruitment_requisitions SET department_id = ?", other);
            assertThatThrownBy(() -> jdbc.update("DELETE FROM departments WHERE id=?", fixture.departmentId()))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("requisition_headcount_overrides_department_id_fkey");
        }
    }

    private static Map<String, Object> row(Fixture fixture, Object... changes) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", UUID.randomUUID());
        row.put("requisition_id", fixture.requisitionId());
        row.put("department_id", fixture.departmentId());
        row.put("plan_year", 2026);
        row.put("headcount_limit", 3);
        row.put("salary_budget", null);
        row.put("headcount_used", 3L);
        row.put("salary_cost_used", 0L);
        row.put("requested_headcount", 2);
        row.put("requested_salary_cost", 600_000_000L);
        row.put("headcount_exceeded", true);
        row.put("salary_budget_exceeded", false);
        row.put("reason", "Dự án mới cần thêm người.");
        row.put("overridden_by", fixture.hrId());
        row.put("overridden_at", CREATED_AT);
        for (int index = 0; index < changes.length; index += 2) {
            row.put((String) changes[index], changes[index + 1]);
        }
        return row;
    }

    private static UUID insert(JdbcTemplate jdbc, Map<String, Object> row) {
        jdbc.update("INSERT INTO requisition_headcount_overrides (" + String.join(",", row.keySet()) + ") VALUES ("
                + String.join(",", row.keySet().stream().map(key -> "?").toList()) + ")", row.values().toArray());
        return (UUID) row.get("id");
    }

    private static void assertViolation(JdbcTemplate jdbc, Map<String, Object> row, String constraint) {
        assertThatThrownBy(() -> insert(jdbc, row)).as(constraint)
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

    // An HR manager (only referenced by the exceptions), the head of IT, one position and one draft of IT.
    private static Fixture insertFixture(JdbcTemplate jdbc) {
        UUID hrId = insertAccount(jdbc, "hr@example.test", "HR_MANAGER");
        UUID headId = insertAccount(jdbc, "head@example.test", "HIRING_MANAGER");
        UUID departmentId = UUID.randomUUID();
        jdbc.update("INSERT INTO departments (id,code,name,manager_user_id) VALUES (?,?,?,?)",
                departmentId, "IT", "Công nghệ thông tin", headId);
        UUID positionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO positions (id,code,name,level,salary_min,salary_max,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, positionId, "DEV", "Lập trình viên", "Junior", 15_000_000L, 25_000_000L, CREATED_AT, CREATED_AT);
        UUID requisitionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO recruitment_requisitions (id,position_id,department_id,headcount,reason,created_by,
                    created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, requisitionId, positionId, departmentId, 2, "NEW_HEADCOUNT", headId, CREATED_AT, CREATED_AT);
        return new Fixture(hrId, departmentId, requisitionId);
    }

    private static UUID insertAccount(JdbcTemplate jdbc, String email, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO user_accounts (id,email,full_name,password_hash,created_at) VALUES (?,?,?,?,?)",
                id, email, "Tài khoản kiểm thử", "unchanged-password-hash", CREATED_AT);
        jdbc.update("INSERT INTO user_roles (user_id,role) VALUES (?,?)", id, role);
        return id;
    }

    private record Fixture(UUID hrId, UUID departmentId, UUID requisitionId) { }
}
