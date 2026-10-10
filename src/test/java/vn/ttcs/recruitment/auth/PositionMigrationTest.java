package vn.ttcs.recruitment.auth;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PositionMigrationTest {
    private static final Timestamp CREATED_AT = Timestamp.from(Instant.parse("2026-10-06T00:00:00Z"));
    private static final long JUNIOR_MIN = 15_000_000L;
    private static final long JUNIOR_MAX = 25_000_000L;
    // Larger than Integer.MAX_VALUE, so an INTEGER column could not hold it.
    private static final long THREE_BILLION_VND = 3_000_000_000L;

    @Test
    void upgradesV6WithoutChangingAccountsDepartmentsOrPermissionGrants() throws Exception {
        try (var postgres = startPostgres()) {
            var dataSource = postgres.getPostgresDatabase();
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                    .target("6").load().migrate();
            var jdbc = new JdbcTemplate(dataSource);
            UUID managerId = insertAccount(jdbc, "manager@example.test");
            jdbc.update("INSERT INTO user_roles (user_id,role) VALUES (?, 'HR_MANAGER')", managerId);
            jdbc.update("INSERT INTO departments (id,code,name,manager_user_id) VALUES (?,?,?,?)",
                    UUID.randomUUID(), "HR", "Nhân sự", managerId);
            var previousAccounts = jdbc.queryForList("SELECT * FROM user_accounts ORDER BY id");
            var previousRoles = jdbc.queryForList("SELECT * FROM user_roles ORDER BY user_id,role");
            var previousDepartments = jdbc.queryForList("SELECT * FROM departments ORDER BY id");
            var previousPermissions = jdbc.queryForList("SELECT * FROM permissions ORDER BY code");
            var previousGrants = jdbc.queryForList(
                    "SELECT * FROM role_permissions ORDER BY role_code,permission_code");

            var flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                    .target("7").load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            flyway.validate();

            assertThat(jdbc.queryForList("SELECT * FROM user_accounts ORDER BY id")).isEqualTo(previousAccounts);
            assertThat(jdbc.queryForList("SELECT * FROM user_roles ORDER BY user_id,role")).isEqualTo(previousRoles);
            assertThat(jdbc.queryForList("SELECT * FROM departments ORDER BY id")).isEqualTo(previousDepartments);
            assertThat(jdbc.queryForList("SELECT * FROM permissions ORDER BY code")).isEqualTo(previousPermissions);
            assertThat(jdbc.queryForList("SELECT * FROM role_permissions ORDER BY role_code,permission_code"))
                    .isEqualTo(previousGrants);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM positions", Integer.class)).isZero();
        }
    }

    @Test
    void upgradesV7ByAddingSalaryRangePermissionsForTheHrManagerOnly() throws Exception {
        try (var postgres = startPostgres()) {
            var dataSource = postgres.getPostgresDatabase();
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                    .target("7").load().migrate();
            var jdbc = new JdbcTemplate(dataSource);
            UUID managerId = insertAccount(jdbc, "manager@example.test");
            jdbc.update("INSERT INTO user_roles (user_id,role) VALUES (?, 'HR_MANAGER')", managerId);
            insertJuniorDeveloper(jdbc);
            var previousAccounts = jdbc.queryForList("SELECT * FROM user_accounts ORDER BY id");
            var previousRoles = jdbc.queryForList("SELECT * FROM user_roles ORDER BY user_id,role");
            var previousPositions = jdbc.queryForList("SELECT * FROM positions ORDER BY id");
            var previousPermissions = jdbc.queryForList("SELECT code FROM permissions", String.class);
            var previousGrants = jdbc.queryForList("SELECT role_code || ':' || permission_code FROM role_permissions",
                    String.class);

            var flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                    .target("7.1").load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            flyway.validate();

            assertThat(jdbc.queryForList("SELECT * FROM user_accounts ORDER BY id")).isEqualTo(previousAccounts);
            assertThat(jdbc.queryForList("SELECT * FROM user_roles ORDER BY user_id,role")).isEqualTo(previousRoles);
            assertThat(jdbc.queryForList("SELECT * FROM positions ORDER BY id")).isEqualTo(previousPositions);
            // Every earlier code and grant is kept; the upgrade only adds four codes and two HR manager grants.
            var expectedPermissions = new ArrayList<>(previousPermissions);
            expectedPermissions.addAll(List.of("SALARY_RANGES_READ_ALL", "SALARY_RANGES_WRITE_ALL",
                    "SALARY_RANGES_READ_SCOPED", "SALARY_RANGES_WRITE_SCOPED"));
            assertThat(jdbc.queryForList("SELECT code FROM permissions", String.class))
                    .containsExactlyInAnyOrderElementsOf(expectedPermissions);
            var expectedGrants = new ArrayList<>(previousGrants);
            expectedGrants.addAll(List.of("HR_MANAGER:SALARY_RANGES_READ_ALL", "HR_MANAGER:SALARY_RANGES_WRITE_ALL"));
            assertThat(jdbc.queryForList("SELECT role_code || ':' || permission_code FROM role_permissions",
                    String.class)).containsExactlyInAnyOrderElementsOf(expectedGrants);
        }
    }

    @Test
    void storesCodeNameLevelAndSalaryBandInWholeVnd() throws Exception {
        try (var postgres = startPostgres()) {
            var jdbc = migrateAll(postgres);
            UUID juniorId = insertJuniorDeveloper(jdbc);
            UUID directorId = insertPosition(jdbc, "CEO", "Giám đốc điều hành", "Director",
                    1_000_000_000L, THREE_BILLION_VND);
            UUID fixedId = insertPosition(jdbc, "INTERN", "Thực tập sinh", "Intern", 0L, 0L);

            var junior = jdbc.queryForMap("SELECT * FROM positions WHERE id=?", juniorId);
            assertThat(junior.get("code")).isEqualTo("DEV_JUNIOR");
            assertThat(junior.get("name")).isEqualTo("Lập trình viên");
            assertThat(junior.get("level")).isEqualTo("Junior");
            assertThat(junior.get("salary_min")).isEqualTo(JUNIOR_MIN);
            assertThat(junior.get("salary_max")).isEqualTo(JUNIOR_MAX);
            assertThat(junior.get("active")).isEqualTo(true);
            assertThat(junior.get("created_at")).isEqualTo(CREATED_AT);
            assertThat(junior.get("updated_at")).isEqualTo(CREATED_AT);
            assertThat(jdbc.queryForObject("SELECT salary_max FROM positions WHERE id=?", Long.class, directorId))
                    .isEqualTo(THREE_BILLION_VND);
            assertThat(jdbc.queryForObject("SELECT salary_min = salary_max FROM positions WHERE id=?",
                    Boolean.class, fixedId)).isTrue();

            jdbc.update("UPDATE positions SET active=FALSE WHERE id=?", juniorId);
            assertThat(jdbc.queryForObject("SELECT active FROM positions WHERE id=?", Boolean.class, juniorId))
                    .isFalse();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM positions", Integer.class)).isEqualTo(3);
        }
    }

    @Test
    void rejectsDuplicateCodeNegativeSalaryAndReversedSalaryBand() throws Exception {
        try (var postgres = startPostgres()) {
            var jdbc = migrateAll(postgres);
            UUID positionId = insertJuniorDeveloper(jdbc);

            assertThatThrownBy(() -> insertPosition(jdbc, "DEV_JUNIOR", "Mã trùng", "Junior", 1L, 2L))
                    .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("positions_code_key");
            assertThatThrownBy(() -> insertPosition(jdbc, "NEGATIVE", "Lương âm", "Junior", -1L, 10_000_000L))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("non_negative_position_salary_min");
            assertThatThrownBy(() -> insertPosition(jdbc, "ALL_NEGATIVE", "Lương âm", "Junior", -20L, -10L))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("non_negative_position_salary_min");
            assertThatThrownBy(() -> insertPosition(jdbc, "REVERSED", "Dải đảo ngược", "Junior",
                    JUNIOR_MAX, JUNIOR_MIN))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("valid_position_salary_range");
            assertThatThrownBy(() -> jdbc.update("UPDATE positions SET salary_max=salary_min-1 WHERE id=?", positionId))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("valid_position_salary_range");
            assertThatThrownBy(() -> jdbc.update("UPDATE positions SET salary_min=-1 WHERE id=?", positionId))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("non_negative_position_salary_min");

            assertThat(jdbc.queryForObject("SELECT count(*) FROM positions", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT salary_min FROM positions WHERE id=?", Long.class, positionId))
                    .isEqualTo(JUNIOR_MIN);
            assertThat(jdbc.queryForObject("SELECT salary_max FROM positions WHERE id=?", Long.class, positionId))
                    .isEqualTo(JUNIOR_MAX);
        }
    }

    @Test
    void rejectsMissingBlankPaddedOrTooLongFields() throws Exception {
        try (var postgres = startPostgres()) {
            var jdbc = migrateAll(postgres);
            UUID positionId = insertJuniorDeveloper(jdbc);

            for (String column : new String[]{"code", "name", "level", "salary_min", "salary_max", "active",
                    "created_at", "updated_at"}) {
                String sql = "UPDATE positions SET " + column + "=NULL WHERE id=?";
                assertThatThrownBy(() -> jdbc.update(sql, positionId))
                        .as(column).isInstanceOf(DataIntegrityViolationException.class);
            }
            for (String invalid : new String[]{"", " ", " DEV", "DEV "}) {
                assertThatThrownBy(() -> insertPosition(jdbc, invalid, "Chức danh", "Junior", 1L, 2L))
                        .isInstanceOf(DataIntegrityViolationException.class)
                        .hasMessageContaining("valid_position_code");
            }
            for (String invalid : new String[]{"", " ", " Lập trình viên", "Lập trình viên "}) {
                assertThatThrownBy(() -> insertPosition(jdbc, "INVALID_NAME", invalid, "Junior", 1L, 2L))
                        .isInstanceOf(DataIntegrityViolationException.class)
                        .hasMessageContaining("valid_position_name");
            }
            for (String invalid : new String[]{"", " ", " Junior", "Junior "}) {
                assertThatThrownBy(() -> insertPosition(jdbc, "INVALID_LEVEL", "Chức danh", invalid, 1L, 2L))
                        .isInstanceOf(DataIntegrityViolationException.class)
                        .hasMessageContaining("valid_position_level");
            }
            assertThatThrownBy(() -> insertPosition(jdbc, "C".repeat(51), "Chức danh", "Junior", 1L, 2L))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> insertPosition(jdbc, "LONG_NAME", "N".repeat(256), "Junior", 1L, 2L))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> insertPosition(jdbc, "LONG_LEVEL", "Chức danh", "L".repeat(51), 1L, 2L))
                    .isInstanceOf(DataIntegrityViolationException.class);

            UUID longestId = insertPosition(jdbc, "C".repeat(50), "N".repeat(255), "L".repeat(50), 1L, 2L);
            assertThat(jdbc.queryForObject("SELECT level FROM positions WHERE id=?", String.class, longestId))
                    .isEqualTo("L".repeat(50));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM positions", Integer.class)).isEqualTo(2);
        }
    }

    private static EmbeddedPostgres startPostgres() throws Exception {
        return EmbeddedPostgres.builder().setPort(0)
                .setServerConfig("listen_addresses", "127.0.0.1").start();
    }

    private static JdbcTemplate migrateAll(EmbeddedPostgres postgres) {
        var dataSource = postgres.getPostgresDatabase();
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        return new JdbcTemplate(dataSource);
    }

    private static UUID insertAccount(JdbcTemplate jdbc, String email) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO user_accounts (id,email,full_name,password_hash,created_at) VALUES (?,?,?,?,?)",
                id, email, "Tài khoản kiểm thử", "unchanged-password-hash", CREATED_AT);
        return id;
    }

    private static UUID insertJuniorDeveloper(JdbcTemplate jdbc) {
        return insertPosition(jdbc, "DEV_JUNIOR", "Lập trình viên", "Junior", JUNIOR_MIN, JUNIOR_MAX);
    }

    private static UUID insertPosition(JdbcTemplate jdbc, String code, String name, String level,
                                       long salaryMin, long salaryMax) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO positions (id,code,name,level,salary_min,salary_max,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, id, code, name, level, salaryMin, salaryMax, CREATED_AT, CREATED_AT);
        return id;
    }
}
