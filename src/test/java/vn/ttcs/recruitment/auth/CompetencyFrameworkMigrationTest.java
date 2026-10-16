package vn.ttcs.recruitment.auth;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompetencyFrameworkMigrationTest {
    private static final Timestamp CREATED_AT = Timestamp.from(Instant.parse("2026-10-07T00:00:00Z"));
    // Column list of positions before V8, so rows can be compared before and after the upgrade.
    private static final String V7_POSITION_COLUMNS =
            "id,code,name,level,salary_min,salary_max,active,created_at,updated_at";

    @Test
    void upgradesV7_1WithoutChangingExistingDataOrPermissions() throws Exception {
        try (var postgres = startPostgres()) {
            var dataSource = postgres.getPostgresDatabase();
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                    .target("7.1").load().migrate();
            var jdbc = new JdbcTemplate(dataSource);
            UUID managerId = insertAccount(jdbc, "manager@example.test");
            jdbc.update("INSERT INTO user_roles (user_id,role) VALUES (?, 'HR_MANAGER')", managerId);
            jdbc.update("INSERT INTO departments (id,code,name,manager_user_id) VALUES (?,?,?,?)",
                    UUID.randomUUID(), "HR", "Nhân sự", managerId);
            UUID positionId = insertPosition(jdbc, "DEV_JUNIOR", "Lập trình viên");
            var previousAccounts = jdbc.queryForList("SELECT * FROM user_accounts ORDER BY id");
            var previousRoles = jdbc.queryForList("SELECT * FROM user_roles ORDER BY user_id,role");
            var previousDepartments = jdbc.queryForList("SELECT * FROM departments ORDER BY id");
            var previousPositions = jdbc.queryForList("SELECT " + V7_POSITION_COLUMNS + " FROM positions ORDER BY id");
            var previousPermissions = jdbc.queryForList("SELECT * FROM permissions ORDER BY code");
            var previousGrants = jdbc.queryForList(
                    "SELECT * FROM role_permissions ORDER BY role_code,permission_code");

            var flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                    .target("8").load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            flyway.validate();

            assertThat(jdbc.queryForList("SELECT * FROM user_accounts ORDER BY id")).isEqualTo(previousAccounts);
            assertThat(jdbc.queryForList("SELECT * FROM user_roles ORDER BY user_id,role")).isEqualTo(previousRoles);
            assertThat(jdbc.queryForList("SELECT * FROM departments ORDER BY id")).isEqualTo(previousDepartments);
            assertThat(jdbc.queryForList("SELECT " + V7_POSITION_COLUMNS + " FROM positions ORDER BY id"))
                    .isEqualTo(previousPositions);
            assertThat(jdbc.queryForList("SELECT * FROM permissions ORDER BY code")).isEqualTo(previousPermissions);
            assertThat(jdbc.queryForList("SELECT * FROM role_permissions ORDER BY role_code,permission_code"))
                    .isEqualTo(previousGrants);
            // The existing position keeps working without a framework.
            assertThat(jdbc.queryForObject("SELECT competency_framework_id FROM positions WHERE id=?",
                    UUID.class, positionId)).isNull();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM competency_frameworks", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM competency_criteria", Integer.class)).isZero();
        }
    }

    @Test
    void storesOneFrameworkWithWeightedCriteriaSharedByManyPositions() throws Exception {
        try (var postgres = startPostgres()) {
            var jdbc = migrateAll(postgres);
            UUID frameworkId = insertFramework(jdbc, "DEV_CORE", "Năng lực lập trình viên");
            UUID codingId = insertCriterion(jdbc, frameworkId, "Kỹ năng lập trình", "40.00", 1);
            UUID designId = insertCriterion(jdbc, frameworkId, "Thiết kế hệ thống", "35.50", 2);
            UUID teamworkId = insertCriterion(jdbc, frameworkId, "Làm việc nhóm", "24.50", 3);
            UUID juniorId = insertPosition(jdbc, "DEV_JUNIOR", "Lập trình viên Junior");
            UUID seniorId = insertPosition(jdbc, "DEV_SENIOR", "Lập trình viên Senior");
            jdbc.update("UPDATE positions SET competency_framework_id=? WHERE id IN (?,?)",
                    frameworkId, juniorId, seniorId);

            var framework = jdbc.queryForMap("SELECT * FROM competency_frameworks WHERE id=?", frameworkId);
            assertThat(framework.get("code")).isEqualTo("DEV_CORE");
            assertThat(framework.get("name")).isEqualTo("Năng lực lập trình viên");
            assertThat(framework.get("description")).isNull();
            // A framework inserted without a status starts as DRAFT.
            assertThat(framework.get("status")).isEqualTo("DRAFT");
            assertThat(framework.get("created_at")).isEqualTo(CREATED_AT);
            assertThat(framework.get("updated_at")).isEqualTo(CREATED_AT);

            var criteria = jdbc.queryForList(
                    "SELECT id,name,weight,sort_order FROM competency_criteria WHERE framework_id=? ORDER BY sort_order",
                    frameworkId);
            assertThat(criteria).extracting(row -> row.get("id")).containsExactly(codingId, designId, teamworkId);
            // NUMERIC(5,2) keeps the exact value with two decimals, so the total is exactly 100.00.
            assertThat(criteria).extracting(row -> row.get("weight"))
                    .containsExactly(new BigDecimal("40.00"), new BigDecimal("35.50"), new BigDecimal("24.50"));
            assertThat(jdbc.queryForObject("SELECT sum(weight) FROM competency_criteria WHERE framework_id=?",
                    BigDecimal.class, frameworkId)).isEqualTo(new BigDecimal("100.00"));

            // Both positions reach the same three criteria rows: nothing was copied per position.
            var criteriaPerPosition = jdbc.queryForList("""
                    SELECT p.code, c.id FROM positions p
                    JOIN competency_criteria c ON c.framework_id = p.competency_framework_id
                    ORDER BY p.code, c.sort_order
                    """);
            assertThat(criteriaPerPosition).extracting(row -> row.get("code") + ":" + row.get("id"))
                    .containsExactly("DEV_JUNIOR:" + codingId, "DEV_JUNIOR:" + designId, "DEV_JUNIOR:" + teamworkId,
                            "DEV_SENIOR:" + codingId, "DEV_SENIOR:" + designId, "DEV_SENIOR:" + teamworkId);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM competency_criteria", Integer.class)).isEqualTo(3);

            jdbc.update("UPDATE competency_frameworks SET status='ACTIVE', description=? WHERE id=?",
                    "Dùng cho mọi cấp lập trình viên", frameworkId);
            assertThat(jdbc.queryForObject("SELECT status FROM competency_frameworks WHERE id=?",
                    String.class, frameworkId)).isEqualTo("ACTIVE");
        }
    }

    @Test
    void acceptsWeightBoundariesAndRoundsAThirdDecimal() throws Exception {
        try (var postgres = startPostgres()) {
            var jdbc = migrateAll(postgres);
            UUID singleId = insertFramework(jdbc, "SINGLE", "Một tiêu chí");
            UUID otherId = insertFramework(jdbc, "OTHER", "Khung khác");

            UUID fullId = insertCriterion(jdbc, singleId, "Toàn bộ", "100", 1);
            UUID smallestId = insertCriterion(jdbc, otherId, "Nhỏ nhất", "0.01", 1);
            // The same criterion name and sort order are allowed in another framework.
            UUID sameNameId = insertCriterion(jdbc, otherId, "Toàn bộ", "50", 2);
            // NUMERIC(5,2) rounds instead of rejecting, which is why the API must refuse a third decimal itself.
            UUID roundedId = insertCriterion(jdbc, otherId, "Làm tròn", "33.335", 3);

            assertThat(weightOf(jdbc, fullId)).isEqualTo(new BigDecimal("100.00"));
            assertThat(weightOf(jdbc, smallestId)).isEqualTo(new BigDecimal("0.01"));
            assertThat(weightOf(jdbc, sameNameId)).isEqualTo(new BigDecimal("50.00"));
            assertThat(weightOf(jdbc, roundedId)).isEqualTo(new BigDecimal("33.34"));
        }
    }

    @Test
    void rejectsInvalidFrameworks() throws Exception {
        try (var postgres = startPostgres()) {
            var jdbc = migrateAll(postgres);
            UUID frameworkId = insertFramework(jdbc, "DEV_CORE", "Năng lực lập trình viên");

            assertThatThrownBy(() -> insertFramework(jdbc, "DEV_CORE", "Mã trùng"))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("competency_frameworks_code_key");
            for (String invalid : new String[]{"ARCHIVED", "draft", ""}) {
                assertThatThrownBy(() -> jdbc.update("UPDATE competency_frameworks SET status=? WHERE id=?",
                        invalid, frameworkId))
                        .as(invalid).isInstanceOf(DataIntegrityViolationException.class)
                        .hasMessageContaining("valid_competency_framework_status");
            }
            for (String column : new String[]{"code", "name", "status", "created_at", "updated_at"}) {
                String sql = "UPDATE competency_frameworks SET " + column + "=NULL WHERE id=?";
                assertThatThrownBy(() -> jdbc.update(sql, frameworkId))
                        .as(column).isInstanceOf(DataIntegrityViolationException.class);
            }
            for (String invalid : new String[]{"", " ", " DEV", "DEV "}) {
                assertThatThrownBy(() -> insertFramework(jdbc, invalid, "Khung"))
                        .isInstanceOf(DataIntegrityViolationException.class)
                        .hasMessageContaining("valid_competency_framework_code");
            }
            for (String invalid : new String[]{"", " ", " Khung", "Khung "}) {
                assertThatThrownBy(() -> insertFramework(jdbc, "INVALID_NAME", invalid))
                        .isInstanceOf(DataIntegrityViolationException.class)
                        .hasMessageContaining("valid_competency_framework_name");
            }
            for (String invalid : new String[]{"", " ", "\nMô tả", "Mô tả\t"}) {
                assertThatThrownBy(() -> jdbc.update("UPDATE competency_frameworks SET description=? WHERE id=?",
                        invalid, frameworkId))
                        .isInstanceOf(DataIntegrityViolationException.class)
                        .hasMessageContaining("valid_competency_framework_description");
            }
            assertThatThrownBy(() -> insertFramework(jdbc, "C".repeat(51), "Khung"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> insertFramework(jdbc, "LONG_NAME", "N".repeat(256)))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("UPDATE competency_frameworks SET description=? WHERE id=?",
                    "D".repeat(1001), frameworkId))
                    .isInstanceOf(DataIntegrityViolationException.class);

            UUID longestId = insertFramework(jdbc, "C".repeat(50), "N".repeat(255));
            jdbc.update("UPDATE competency_frameworks SET description=? WHERE id=?", "D".repeat(1000), longestId);
            assertThat(jdbc.queryForObject("SELECT length(description) FROM competency_frameworks WHERE id=?",
                    Integer.class, longestId)).isEqualTo(1000);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM competency_frameworks", Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT status FROM competency_frameworks WHERE id=?",
                    String.class, frameworkId)).isEqualTo("DRAFT");
        }
    }

    @Test
    void rejectsInvalidCriteria() throws Exception {
        try (var postgres = startPostgres()) {
            var jdbc = migrateAll(postgres);
            UUID frameworkId = insertFramework(jdbc, "DEV_CORE", "Năng lực lập trình viên");
            UUID criterionId = insertCriterion(jdbc, frameworkId, "Giao tiếp", "30", 1);

            for (String invalid : new String[]{"0", "0.00", "-1", "100.01"}) {
                assertThatThrownBy(() -> insertCriterion(jdbc, frameworkId, "Trọng số " + invalid, invalid, 2))
                        .as(invalid).isInstanceOf(DataIntegrityViolationException.class)
                        .hasMessageContaining("valid_competency_criterion_weight");
            }
            // 1000 does not fit NUMERIC(5,2) at all (at most 999.99).
            assertThatThrownBy(() -> insertCriterion(jdbc, frameworkId, "Quá lớn", "1000", 2))
                    .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("numeric field overflow");
            for (int invalid : new int[]{0, -1}) {
                assertThatThrownBy(() -> insertCriterion(jdbc, frameworkId, "Thứ tự " + invalid, "10", invalid))
                        .isInstanceOf(DataIntegrityViolationException.class)
                        .hasMessageContaining("valid_competency_criterion_sort_order");
            }
            assertThatThrownBy(() -> insertCriterion(jdbc, frameworkId, "Giao tiếp", "10", 2))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("competency_criteria_framework_name_key");
            assertThatThrownBy(() -> insertCriterion(jdbc, frameworkId, "Tư duy", "10", 1))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("competency_criteria_framework_sort_order_key");
            assertThatThrownBy(() -> insertCriterion(jdbc, UUID.randomUUID(), "Không có khung", "10", 1))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("competency_criteria_framework_id_fkey");
            for (String column : new String[]{"framework_id", "name", "weight", "sort_order"}) {
                String sql = "UPDATE competency_criteria SET " + column + "=NULL WHERE id=?";
                assertThatThrownBy(() -> jdbc.update(sql, criterionId))
                        .as(column).isInstanceOf(DataIntegrityViolationException.class);
            }
            for (String invalid : new String[]{"", " ", " Tư duy", "Tư duy "}) {
                assertThatThrownBy(() -> insertCriterion(jdbc, frameworkId, invalid, "10", 2))
                        .isInstanceOf(DataIntegrityViolationException.class)
                        .hasMessageContaining("valid_competency_criterion_name");
            }
            for (String invalid : new String[]{"", " ", "\nMô tả", "Mô tả\t"}) {
                assertThatThrownBy(() -> jdbc.update("UPDATE competency_criteria SET description=? WHERE id=?",
                        invalid, criterionId))
                        .isInstanceOf(DataIntegrityViolationException.class)
                        .hasMessageContaining("valid_competency_criterion_description");
            }
            assertThatThrownBy(() -> insertCriterion(jdbc, frameworkId, "N".repeat(256), "10", 2))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("UPDATE competency_criteria SET description=? WHERE id=?",
                    "D".repeat(1001), criterionId))
                    .isInstanceOf(DataIntegrityViolationException.class);

            jdbc.update("UPDATE competency_criteria SET description=? WHERE id=?", "Trình bày rõ ràng", criterionId);
            var criterion = jdbc.queryForMap("SELECT * FROM competency_criteria WHERE id=?", criterionId);
            assertThat(criterion.get("name")).isEqualTo("Giao tiếp");
            assertThat(criterion.get("description")).isEqualTo("Trình bày rõ ràng");
            assertThat(criterion.get("weight")).isEqualTo(new BigDecimal("30.00"));
            assertThat(criterion.get("sort_order")).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM competency_criteria", Integer.class)).isEqualTo(1);
        }
    }

    @Test
    void checksCriterionUniquenessWhenTheTransactionCommits() throws Exception {
        try (var postgres = startPostgres()) {
            var jdbc = migrateAll(postgres);
            // The transaction manager must use the same DataSource object as jdbc, or jdbc would not join it.
            var transaction = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
            UUID frameworkId = insertFramework(jdbc, "DEV_CORE", "Năng lực lập trình viên");
            UUID codingId = insertCriterion(jdbc, frameworkId, "Kỹ năng lập trình", "60", 1);
            UUID teamworkId = insertCriterion(jdbc, frameworkId, "Làm việc nhóm", "40", 2);

            // Swapping sort order and names needs a temporary duplicate between the two UPDATE statements.
            transaction.executeWithoutResult(status -> {
                jdbc.update("UPDATE competency_criteria SET sort_order=2, name='Làm việc nhóm' WHERE id=?", codingId);
                jdbc.update("UPDATE competency_criteria SET sort_order=1, name='Kỹ năng lập trình' WHERE id=?",
                        teamworkId);
            });
            assertThat(jdbc.queryForList(
                    "SELECT id FROM competency_criteria WHERE framework_id=? ORDER BY sort_order", UUID.class,
                    frameworkId)).containsExactly(teamworkId, codingId);

            // A duplicate still present at COMMIT fails, and the whole transaction is rolled back.
            // The plain JDBC transaction manager reports a failed commit as TransactionSystemException;
            // JPA translates the same failure to DataIntegrityViolationException (see the repository test).
            assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                jdbc.update("UPDATE competency_criteria SET weight=50 WHERE id=?", codingId);
                insertCriterion(jdbc, frameworkId, "Tư duy", "10", 1);
            })).isInstanceOf(TransactionSystemException.class)
                    .rootCause().hasMessageContaining("competency_criteria_framework_sort_order_key");
            assertThat(weightOf(jdbc, codingId)).isEqualTo(new BigDecimal("60.00"));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM competency_criteria", Integer.class)).isEqualTo(2);
        }
    }

    @Test
    void protectsFrameworksUsedByPositionsAndCascadesCriteriaOfUnusedOnes() throws Exception {
        try (var postgres = startPostgres()) {
            var jdbc = migrateAll(postgres);
            UUID frameworkId = insertFramework(jdbc, "DEV_CORE", "Năng lực lập trình viên");
            insertCriterion(jdbc, frameworkId, "Kỹ năng lập trình", "100", 1);
            UUID positionId = insertPosition(jdbc, "DEV_JUNIOR", "Lập trình viên");
            jdbc.update("UPDATE positions SET competency_framework_id=? WHERE id=?", frameworkId, positionId);

            assertThatThrownBy(() -> jdbc.update("DELETE FROM competency_frameworks WHERE id=?", frameworkId))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("positions_competency_framework_id_fkey");
            assertThatThrownBy(() -> jdbc.update("UPDATE positions SET competency_framework_id=? WHERE id=?",
                    UUID.randomUUID(), positionId))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("positions_competency_framework_id_fkey");
            assertThat(jdbc.queryForObject("SELECT competency_framework_id FROM positions WHERE id=?",
                    UUID.class, positionId)).isEqualTo(frameworkId);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM competency_criteria", Integer.class)).isEqualTo(1);

            // Once no position uses it, deleting the framework also deletes its criteria.
            jdbc.update("UPDATE positions SET competency_framework_id=NULL WHERE id=?", positionId);
            assertThat(jdbc.update("DELETE FROM competency_frameworks WHERE id=?", frameworkId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM competency_criteria", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM positions", Integer.class)).isEqualTo(1);
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

    private static UUID insertPosition(JdbcTemplate jdbc, String code, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO positions (id,code,name,level,salary_min,salary_max,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, id, code, name, "Junior", 15_000_000L, 25_000_000L, CREATED_AT, CREATED_AT);
        return id;
    }

    private static UUID insertFramework(JdbcTemplate jdbc, String code, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO competency_frameworks (id,code,name,created_at,updated_at) VALUES (?,?,?,?,?)",
                id, code, name, CREATED_AT, CREATED_AT);
        return id;
    }

    private static UUID insertCriterion(JdbcTemplate jdbc, UUID frameworkId, String name, String weight,
                                        int sortOrder) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO competency_criteria (id,framework_id,name,weight,sort_order)
                VALUES (?,?,?,?,?)
                """, id, frameworkId, name, new BigDecimal(weight), sortOrder);
        return id;
    }

    private static BigDecimal weightOf(JdbcTemplate jdbc, UUID criterionId) {
        return jdbc.queryForObject("SELECT weight FROM competency_criteria WHERE id=?", BigDecimal.class, criterionId);
    }
}
