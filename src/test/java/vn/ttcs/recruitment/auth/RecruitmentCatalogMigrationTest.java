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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecruitmentCatalogMigrationTest {
    private static final Timestamp CREATED_AT = Timestamp.from(Instant.parse("2026-10-07T00:00:00Z"));
    private static final MigrationVersion CATALOG_VERSION = MigrationVersion.fromVersion("10");

    @Test
    void upgradesWithoutChangingAccountsDepartmentsOrPermissionGrants() throws Exception {
        try (var postgres = startPostgres()) {
            var dataSource = postgres.getPostgresDatabase();
            flyway(dataSource).target(latestVersionBefore(dataSource, CATALOG_VERSION)).load().migrate();
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

            var flyway = flyway(dataSource).target(CATALOG_VERSION).load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            flyway.validate();

            assertThat(jdbc.queryForList("SELECT * FROM user_accounts ORDER BY id")).isEqualTo(previousAccounts);
            assertThat(jdbc.queryForList("SELECT * FROM user_roles ORDER BY user_id,role")).isEqualTo(previousRoles);
            assertThat(jdbc.queryForList("SELECT * FROM departments ORDER BY id")).isEqualTo(previousDepartments);
            assertThat(jdbc.queryForList("SELECT * FROM permissions ORDER BY code")).isEqualTo(previousPermissions);
            assertThat(jdbc.queryForList("SELECT * FROM role_permissions ORDER BY role_code,permission_code"))
                    .isEqualTo(previousGrants);
            // No business values are invented: HR fills the catalogs later.
            assertThat(jdbc.queryForObject("SELECT count(*) FROM recruitment_catalog_items", Integer.class)).isZero();
        }
    }

    @Test
    void storesValuesOfAllFourCatalogTypesWithTheirDisplayOrder() throws Exception {
        try (var postgres = startPostgres()) {
            var jdbc = migrateAll(postgres);
            UUID linkedInId = insertItem(jdbc, "CANDIDATE_SOURCE", "LINKEDIN", "LinkedIn", 2);
            insertItem(jdbc, "CANDIDATE_SOURCE", "REFERRAL", "Nhân viên giới thiệu", 1);
            insertItem(jdbc, "CANDIDATE_SOURCE", "OTHER", "Nguồn khác", 3);
            // The same code is allowed again because it belongs to another catalog type.
            insertItem(jdbc, "REJECTION_REASON", "OTHER", "Lý do khác", 2);
            insertItem(jdbc, "REJECTION_REASON", "SKILL_MISMATCH", "Chưa phù hợp kỹ năng", 1);
            insertItem(jdbc, "WORK_LOCATION", "HANOI", "Hà Nội", 0);
            insertItem(jdbc, "EMPLOYMENT_TYPE", "FULL_TIME", "Toàn thời gian", 0);

            var linkedIn = jdbc.queryForMap("SELECT * FROM recruitment_catalog_items WHERE id=?", linkedInId);
            assertThat(linkedIn.get("catalog_type")).isEqualTo("CANDIDATE_SOURCE");
            assertThat(linkedIn.get("code")).isEqualTo("LINKEDIN");
            assertThat(linkedIn.get("name")).isEqualTo("LinkedIn");
            assertThat(linkedIn.get("sort_order")).isEqualTo(2);
            assertThat(linkedIn.get("active")).isEqualTo(true);
            assertThat(linkedIn.get("created_at")).isEqualTo(CREATED_AT);
            assertThat(linkedIn.get("updated_at")).isEqualTo(CREATED_AT);

            assertThat(codesInDisplayOrder(jdbc, "CANDIDATE_SOURCE")).containsExactly("REFERRAL", "LINKEDIN", "OTHER");
            assertThat(codesInDisplayOrder(jdbc, "REJECTION_REASON")).containsExactly("SKILL_MISMATCH", "OTHER");
            assertThat(codesInDisplayOrder(jdbc, "WORK_LOCATION")).containsExactly("HANOI");
            assertThat(codesInDisplayOrder(jdbc, "EMPLOYMENT_TYPE")).containsExactly("FULL_TIME");

            // sort_order is not unique, so a reorder may give two values the same number for a moment.
            jdbc.update("UPDATE recruitment_catalog_items SET sort_order=1 WHERE id=?", linkedInId);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM recruitment_catalog_items
                    WHERE catalog_type='CANDIDATE_SOURCE' AND sort_order=1
                    """, Integer.class)).isEqualTo(2);
            jdbc.update("UPDATE recruitment_catalog_items SET sort_order=0 WHERE id=?", linkedInId);
            assertThat(codesInDisplayOrder(jdbc, "CANDIDATE_SOURCE")).containsExactly("LINKEDIN", "REFERRAL", "OTHER");

            jdbc.update("UPDATE recruitment_catalog_items SET active=FALSE WHERE id=?", linkedInId);
            assertThat(jdbc.queryForObject("SELECT active FROM recruitment_catalog_items WHERE id=?",
                    Boolean.class, linkedInId)).isFalse();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM recruitment_catalog_items", Integer.class))
                    .isEqualTo(7);
        }
    }

    @Test
    void rejectsUnknownTypeDuplicateCodeInSameTypeAndNegativeSortOrder() throws Exception {
        try (var postgres = startPostgres()) {
            var jdbc = migrateAll(postgres);
            UUID itemId = insertItem(jdbc, "CANDIDATE_SOURCE", "LINKEDIN", "LinkedIn", 1);

            for (String invalidType : new String[]{"SKILL", "candidate_source", "", " CANDIDATE_SOURCE"}) {
                assertThatThrownBy(() -> insertItem(jdbc, invalidType, "CODE", "Giá trị", 1))
                        .as(invalidType).isInstanceOf(DataIntegrityViolationException.class)
                        .hasMessageContaining("valid_recruitment_catalog_type");
            }
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE recruitment_catalog_items SET catalog_type='SKILL' WHERE id=?", itemId))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("valid_recruitment_catalog_type");
            assertThatThrownBy(() -> insertItem(jdbc, "CANDIDATE_SOURCE", "LINKEDIN", "Mã trùng", 2))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("recruitment_catalog_items_type_code_key");
            assertThatThrownBy(() -> insertItem(jdbc, "CANDIDATE_SOURCE", "NEGATIVE", "Thứ tự âm", -1))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("non_negative_recruitment_catalog_sort_order");
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE recruitment_catalog_items SET sort_order=-1 WHERE id=?", itemId))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("non_negative_recruitment_catalog_sort_order");

            var unchanged = jdbc.queryForMap("SELECT * FROM recruitment_catalog_items WHERE id=?", itemId);
            assertThat(unchanged.get("catalog_type")).isEqualTo("CANDIDATE_SOURCE");
            assertThat(unchanged.get("name")).isEqualTo("LinkedIn");
            assertThat(unchanged.get("sort_order")).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM recruitment_catalog_items", Integer.class))
                    .isEqualTo(1);
        }
    }

    @Test
    void rejectsMissingBlankPaddedOrTooLongFields() throws Exception {
        try (var postgres = startPostgres()) {
            var jdbc = migrateAll(postgres);
            UUID itemId = insertItem(jdbc, "WORK_LOCATION", "HANOI", "Hà Nội", 1);

            for (String column : new String[]{"catalog_type", "code", "name", "sort_order", "active",
                    "created_at", "updated_at"}) {
                String sql = "UPDATE recruitment_catalog_items SET " + column + "=NULL WHERE id=?";
                assertThatThrownBy(() -> jdbc.update(sql, itemId))
                        .as(column).isInstanceOf(DataIntegrityViolationException.class);
            }
            for (String invalid : new String[]{"", " ", " HANOI", "HANOI "}) {
                assertThatThrownBy(() -> insertItem(jdbc, "WORK_LOCATION", invalid, "Địa điểm", 1))
                        .isInstanceOf(DataIntegrityViolationException.class)
                        .hasMessageContaining("valid_recruitment_catalog_code");
            }
            for (String invalid : new String[]{"", " ", " Hà Nội", "Hà Nội "}) {
                assertThatThrownBy(() -> insertItem(jdbc, "WORK_LOCATION", "INVALID_NAME", invalid, 1))
                        .isInstanceOf(DataIntegrityViolationException.class)
                        .hasMessageContaining("valid_recruitment_catalog_name");
            }
            assertThatThrownBy(() -> insertItem(jdbc, "WORK_LOCATION", "C".repeat(51), "Địa điểm", 1))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> insertItem(jdbc, "WORK_LOCATION", "LONG_NAME", "N".repeat(256), 1))
                    .isInstanceOf(DataIntegrityViolationException.class);

            UUID longestId = insertItem(jdbc, "WORK_LOCATION", "C".repeat(50), "N".repeat(255), 2);
            assertThat(jdbc.queryForObject("SELECT name FROM recruitment_catalog_items WHERE id=?",
                    String.class, longestId)).isEqualTo("N".repeat(255));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM recruitment_catalog_items", Integer.class))
                    .isEqualTo(2);
        }
    }

    @Test
    void everyForeignKeyToACatalogValueBlocksDeletingThatValue() throws Exception {
        try (var postgres = startPostgres()) {
            var jdbc = migrateAll(postgres);
            // The delete API relies on PostgreSQL refusing to delete a value that is still referenced (task 229).
            // ON DELETE CASCADE / SET NULL / SET DEFAULT would silently delete or blank the referencing rows
            // instead, and a DEFERRABLE key could fail only at commit. No migration references catalog values yet;
            // this guard fails as soon as one adds such a foreign key.
            assertThat(unsafeCatalogReferences(jdbc)).isEmpty();

            // The guard really finds those keys, and accepts the default NO ACTION and RESTRICT.
            jdbc.execute("CREATE TABLE cascading_usage (item_id UUID REFERENCES recruitment_catalog_items (id) "
                    + "ON DELETE CASCADE)");
            jdbc.execute("CREATE TABLE clearing_usage (item_id UUID REFERENCES recruitment_catalog_items (id) "
                    + "ON DELETE SET NULL)");
            jdbc.execute("CREATE TABLE deferred_usage (item_id UUID REFERENCES recruitment_catalog_items (id) "
                    + "DEFERRABLE INITIALLY DEFERRED)");
            jdbc.execute("CREATE TABLE default_usage (item_id UUID REFERENCES recruitment_catalog_items (id))");
            jdbc.execute("CREATE TABLE restricted_usage (item_id UUID REFERENCES recruitment_catalog_items (id) "
                    + "ON DELETE RESTRICT)");
            assertThat(unsafeCatalogReferences(jdbc)).containsExactly("cascading_usage_item_id_fkey",
                    "clearing_usage_item_id_fkey", "deferred_usage_item_id_fkey");
        }
    }

    // confdeltype 'a' is NO ACTION and 'r' is RESTRICT: only these two make the DELETE itself fail.
    private static List<String> unsafeCatalogReferences(JdbcTemplate jdbc) {
        return jdbc.queryForList("""
                SELECT conname FROM pg_constraint
                WHERE contype = 'f' AND confrelid = 'recruitment_catalog_items'::regclass
                  AND (confdeltype NOT IN ('a', 'r') OR condeferrable)
                ORDER BY conname
                """, String.class);
    }

    private static EmbeddedPostgres startPostgres() throws Exception {
        return EmbeddedPostgres.builder().setPort(0)
                .setServerConfig("listen_addresses", "127.0.0.1").start();
    }

    private static FluentConfiguration flyway(DataSource dataSource) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration");
    }

    // Other branches add migrations between V7 and V10. Stopping at the newest one before V10
    // keeps the upgrade test about V10 only, whatever was merged before it.
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

    private static UUID insertAccount(JdbcTemplate jdbc, String email) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO user_accounts (id,email,full_name,password_hash,created_at) VALUES (?,?,?,?,?)",
                id, email, "Tài khoản kiểm thử", "unchanged-password-hash", CREATED_AT);
        return id;
    }

    // Leaves out "active" on purpose, so every inserted row also checks the TRUE default.
    private static UUID insertItem(JdbcTemplate jdbc, String catalogType, String code, String name, int sortOrder) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO recruitment_catalog_items (id,catalog_type,code,name,sort_order,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?)
                """, id, catalogType, code, name, sortOrder, CREATED_AT, CREATED_AT);
        return id;
    }

    private static List<String> codesInDisplayOrder(JdbcTemplate jdbc, String catalogType) {
        return jdbc.queryForList(
                "SELECT code FROM recruitment_catalog_items WHERE catalog_type=? ORDER BY sort_order, code",
                String.class, catalogType);
    }
}
