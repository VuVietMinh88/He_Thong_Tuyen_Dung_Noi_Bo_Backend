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
import static org.assertj.core.api.Assertions.tuple;

// Task 272: V14 adds the headcount_plans table and the HEADCOUNT_PLANS permissions of the HR manager.
class HeadcountPlanMigrationTest {
    private static final Timestamp CREATED_AT = Timestamp.from(Instant.parse("2026-10-10T00:00:00Z"));
    private static final Timestamp UPDATED_AT = Timestamp.from(Instant.parse("2026-10-10T02:30:00Z"));
    private static final MigrationVersion PLAN_VERSION = MigrationVersion.fromVersion("14");
    // Larger than Integer.MAX_VALUE, so an INTEGER column could not hold the budget.
    private static final long FIVE_BILLION_VND = 5_000_000_000L;

    @Test
    void upgradesKeepingExistingDataAndOnlyAddsTheHeadcountPlanPermissionsOfTheHrManager() throws Exception {
        try (var postgres = startPostgres()) {
            var dataSource = postgres.getPostgresDatabase();
            flyway(dataSource).target(latestVersionBefore(dataSource, PLAN_VERSION)).load().migrate();
            var jdbc = new JdbcTemplate(dataSource);
            var fixture = insertFixture(jdbc);
            var previousAccounts = jdbc.queryForList("SELECT * FROM user_accounts ORDER BY id");
            var previousDepartments = jdbc.queryForList("SELECT * FROM departments ORDER BY id");
            var previousRequisitions = jdbc.queryForList("SELECT * FROM recruitment_requisitions ORDER BY id");
            var previousPermissions = jdbc.queryForList("SELECT * FROM permissions ORDER BY code");
            var previousGrants = jdbc.queryForList("SELECT * FROM role_permissions ORDER BY role_code,permission_code");

            var flyway = flyway(dataSource).target(PLAN_VERSION).load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            flyway.validate();

            assertThat(flyway.info().current().getVersion()).isEqualTo(PLAN_VERSION);
            assertThat(jdbc.queryForList("SELECT * FROM user_accounts ORDER BY id")).isEqualTo(previousAccounts);
            assertThat(jdbc.queryForList("SELECT * FROM departments ORDER BY id")).isEqualTo(previousDepartments);
            assertThat(jdbc.queryForList("SELECT * FROM recruitment_requisitions ORDER BY id"))
                    .isEqualTo(previousRequisitions);
            // No plan is invented: without a plan a department has no limit until HR declares one.
            assertThat(jdbc.queryForObject("SELECT count(*) FROM headcount_plans", Integer.class)).isZero();

            var newPermissions = jdbc.queryForList("SELECT * FROM permissions ORDER BY code");
            newPermissions.removeAll(previousPermissions);
            assertThat(newPermissions).extracting(row -> row.get("code"), row -> row.get("module_code"),
                    row -> row.get("action_code"), row -> row.get("scope_code")).containsExactlyInAnyOrder(
                    tuple("HEADCOUNT_PLANS_READ_ALL", "HEADCOUNT_PLANS", "READ", "ALL"),
                    tuple("HEADCOUNT_PLANS_WRITE_ALL", "HEADCOUNT_PLANS", "WRITE", "ALL"),
                    tuple("HEADCOUNT_PLANS_READ_SCOPED", "HEADCOUNT_PLANS", "READ", "SCOPED"),
                    tuple("HEADCOUNT_PLANS_WRITE_SCOPED", "HEADCOUNT_PLANS", "WRITE", "SCOPED"));
            assertThat(jdbc.queryForList("SELECT * FROM permissions ORDER BY code")).containsAll(previousPermissions);
            var newGrants = jdbc.queryForList("SELECT * FROM role_permissions ORDER BY role_code,permission_code");
            newGrants.removeAll(previousGrants);
            assertThat(newGrants).extracting(row -> row.get("role_code"), row -> row.get("permission_code"))
                    .containsExactlyInAnyOrder(
                            tuple("HR_MANAGER", "HEADCOUNT_PLANS_READ_ALL"),
                            tuple("HR_MANAGER", "HEADCOUNT_PLANS_WRITE_ALL"));
            assertThat(jdbc.queryForList("SELECT * FROM role_permissions ORDER BY role_code,permission_code"))
                    .containsAll(previousGrants);

            // Existing departments and accounts can be used by a plan right after the upgrade.
            insertPlan(jdbc, fixture, 2026, 3, FIVE_BILLION_VND);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM headcount_plans", Integer.class)).isOne();
        }
    }

    @Test
    void storesOnePlanPerDepartmentAndYearWithAnOptionalBudget() throws Exception {
        try (var postgres = startPostgres()) {
            var jdbc = migrateAll(postgres);
            var fixture = insertFixture(jdbc);
            UUID id = insertPlan(jdbc, fixture, 2026, 3, FIVE_BILLION_VND);

            Map<String, Object> row = jdbc.queryForMap("SELECT * FROM headcount_plans WHERE id=?", id);
            assertThat(row.get("department_id")).isEqualTo(fixture.departmentId());
            assertThat(row.get("plan_year")).isEqualTo(2026);
            assertThat(row.get("headcount_limit")).isEqualTo(3);
            assertThat(row.get("salary_budget")).isEqualTo(FIVE_BILLION_VND);
            assertThat(row.get("created_at")).isEqualTo(CREATED_AT);
            assertThat(row.get("updated_at")).isEqualTo(UPDATED_AT);
            assertThat(row.get("updated_by")).isEqualTo(fixture.hrId());

            // Another year of the same department is another plan; no budget and a limit of 0 are allowed.
            UUID nextYear = insertPlan(jdbc, fixture, 2027, 0, null);
            assertThat(jdbc.queryForObject("SELECT salary_budget FROM headcount_plans WHERE id=?", Long.class,
                    nextYear)).isNull();
            // The edges of the allowed years.
            insertPlan(jdbc, fixture, 2000, 1, 0L);
            insertPlan(jdbc, fixture, 2100, 1, 0L);

            assertViolation(() -> insertPlan(jdbc, fixture, 2026, 5, null), "headcount_plans_department_year_key");
        }
    }

    @Test
    void rejectsYearsOutsideTheRangeNegativeAmountsAndUnknownReferences() throws Exception {
        try (var postgres = startPostgres()) {
            var jdbc = migrateAll(postgres);
            var fixture = insertFixture(jdbc);

            assertViolation(() -> insertPlan(jdbc, fixture, 1999, 1, null), "valid_headcount_plan_year");
            assertViolation(() -> insertPlan(jdbc, fixture, 2101, 1, null), "valid_headcount_plan_year");
            assertViolation(() -> insertPlan(jdbc, fixture, 2026, -1, null), "non_negative_headcount_limit");
            assertViolation(() -> insertPlan(jdbc, fixture, 2026, 1, -1L), "non_negative_salary_budget");
            var unknownDepartment = new Fixture(fixture.hrId(), UUID.randomUUID());
            assertViolation(() -> insertPlan(jdbc, unknownDepartment, 2026, 1, null),
                    "headcount_plans_department_id_fkey");
            var unknownAccount = new Fixture(UUID.randomUUID(), fixture.departmentId());
            assertViolation(() -> insertPlan(jdbc, unknownAccount, 2026, 1, null),
                    "headcount_plans_updated_by_fkey");
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO headcount_plans (id,department_id,plan_year,headcount_limit,created_at,updated_at,
                        updated_by)
                    VALUES (?,?,?,NULL,?,?,?)
                    """, UUID.randomUUID(), fixture.departmentId(), 2026, CREATED_AT, UPDATED_AT, fixture.hrId()))
                    .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("headcount_limit");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM headcount_plans", Integer.class)).isZero();
        }
    }

    @Test
    void deletingAnUnusedDepartmentDeletesItsPlansButTheLastEditorCannotBeDeleted() throws Exception {
        try (var postgres = startPostgres()) {
            var jdbc = migrateAll(postgres);
            var fixture = insertFixture(jdbc);
            insertPlan(jdbc, fixture, 2026, 3, null);
            insertPlan(jdbc, fixture, 2027, 4, null);

            assertViolation(() -> jdbc.update("DELETE FROM user_accounts WHERE id=?", fixture.hrId()),
                    "headcount_plans_updated_by_fkey");

            // Task 197 only deletes a department without requisitions; the plans alone do not keep it.
            jdbc.update("DELETE FROM recruitment_requisitions");
            jdbc.update("DELETE FROM departments WHERE id=?", fixture.departmentId());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM headcount_plans", Integer.class)).isZero();
        }
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

    // The upgrade starts from the newest migration below V14, whichever task added it.
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

    // An HR manager, a department head, one department and one draft requisition of that department.
    private static Fixture insertFixture(JdbcTemplate jdbc) {
        UUID hrId = insertAccount(jdbc, "hr@example.test", "HR_MANAGER");
        UUID managerId = insertAccount(jdbc, "manager@example.test", "HIRING_MANAGER");
        UUID departmentId = UUID.randomUUID();
        jdbc.update("INSERT INTO departments (id,code,name,manager_user_id) VALUES (?,?,?,?)",
                departmentId, "IT", "Công nghệ thông tin", managerId);
        UUID positionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO positions (id,code,name,level,salary_min,salary_max,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, positionId, "DEV_JUNIOR", "Lập trình viên", "Junior", 15_000_000L, 25_000_000L,
                CREATED_AT, CREATED_AT);
        jdbc.update("""
                INSERT INTO recruitment_requisitions (id,position_id,department_id,headcount,reason,created_by,
                    created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, UUID.randomUUID(), positionId, departmentId, 2, "NEW_HEADCOUNT", managerId, CREATED_AT,
                CREATED_AT);
        return new Fixture(hrId, departmentId);
    }

    private static UUID insertAccount(JdbcTemplate jdbc, String email, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO user_accounts (id,email,full_name,password_hash,created_at) VALUES (?,?,?,?,?)",
                id, email, "Tài khoản kiểm thử", "unchanged-password-hash", CREATED_AT);
        jdbc.update("INSERT INTO user_roles (user_id,role) VALUES (?,?)", id, role);
        return id;
    }

    private static UUID insertPlan(JdbcTemplate jdbc, Fixture fixture, int year, int limit, Long budget) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO headcount_plans (id,department_id,plan_year,headcount_limit,salary_budget,created_at,
                    updated_at,updated_by)
                VALUES (?,?,?,?,?,?,?,?)
                """, id, fixture.departmentId(), year, limit, budget, CREATED_AT, UPDATED_AT, fixture.hrId());
        return id;
    }

    private record Fixture(UUID hrId, UUID departmentId) { }
}
