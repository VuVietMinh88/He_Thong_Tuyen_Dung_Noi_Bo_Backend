package vn.ttcs.recruitment.auth;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountProfileMigrationTest {
    private static final Timestamp CREATED_AT = Timestamp.from(Instant.parse("2026-10-05T00:00:00Z"));
    private static final Timestamp EXPIRES_AT = Timestamp.from(CREATED_AT.toInstant().plusSeconds(1800));

    @Test
    void upgradesV4WithoutChangingExistingAccountsRolesSessionsOrActivationLinks() throws Exception {
        try (var postgres = startPostgres()) {
            var dataSource = postgres.getPostgresDatabase();
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                    .target("4").load().migrate();
            var jdbc = new JdbcTemplate(dataSource);
            UUID userId = insertAccount(jdbc, "before-migration@example.test");
            UUID sessionId = UUID.randomUUID();
            jdbc.update("INSERT INTO user_roles (user_id,role) VALUES (?, 'ADMIN')", userId);
            jdbc.update("INSERT INTO auth_sessions (id,user_id,refresh_token_hash,created_at,expires_at) VALUES (?,?,?,?,?)",
                    sessionId, userId, "b".repeat(64), CREATED_AT, EXPIRES_AT);
            jdbc.update("INSERT INTO account_activation_tokens (user_id,token_hash,created_at,expires_at) VALUES (?,?,?,?)",
                    userId, "c".repeat(64), CREATED_AT, EXPIRES_AT);
            var previousAccount = jdbc.queryForMap("SELECT * FROM user_accounts WHERE id=?", userId);
            var previousRoles = jdbc.queryForList("SELECT * FROM user_roles WHERE user_id=?", userId);
            var previousSession = jdbc.queryForMap("SELECT * FROM auth_sessions WHERE id=?", sessionId);
            var previousActivation = jdbc.queryForMap("SELECT * FROM account_activation_tokens WHERE user_id=?", userId);

            var flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                    .target("6").load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(2);
            flyway.validate();

            var upgradedAccount = jdbc.queryForMap("SELECT * FROM user_accounts WHERE id=?", userId);
            assertThat(upgradedAccount).containsAllEntriesOf(previousAccount);
            assertThat(upgradedAccount).containsKeys("phone", "display_title", "department_id");
            assertThat(upgradedAccount.get("phone")).isNull();
            assertThat(upgradedAccount.get("display_title")).isNull();
            assertThat(upgradedAccount.get("department_id")).isNull();
            assertThat(jdbc.queryForList("SELECT * FROM user_roles WHERE user_id=?", userId)).isEqualTo(previousRoles);
            assertThat(jdbc.queryForMap("SELECT * FROM auth_sessions WHERE id=?", sessionId)).isEqualTo(previousSession);
            assertThat(jdbc.queryForMap("SELECT * FROM account_activation_tokens WHERE user_id=?", userId))
                    .isEqualTo(previousActivation);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM departments", Integer.class)).isZero();
            assertThat(jdbc.queryForList("SELECT role_code FROM role_permissions WHERE permission_code='SELF_PROFILE_WRITE'",
                    String.class)).containsExactlyInAnyOrder(
                    "ADMIN", "HR_MANAGER", "RECRUITER", "HIRING_MANAGER", "INTERVIEWER", "APPROVER");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM role_permissions WHERE role_code='CANDIDATE' "
                    + "AND permission_code='SELF_PROFILE_WRITE'", Integer.class)).isZero();
        }
    }

    @Test
    void supportsMultipleDepartmentLevelsAndProtectsReferencedAccountsAndDepartments() throws Exception {
        try (var postgres = startPostgres()) {
            var jdbc = migrateAll(postgres);
            UUID managerId = insertAccount(jdbc, "manager@example.test");
            UUID employeeId = insertAccount(jdbc, "employee@example.test");
            UUID rootId = insertDepartment(jdbc, "COMPANY", "Công ty", null, managerId);
            UUID parentId = insertDepartment(jdbc, "HR", "Nhân sự", rootId, managerId);
            UUID childId = insertDepartment(jdbc, "RECRUITMENT", "Tuyển dụng", parentId, managerId);
            jdbc.update("UPDATE departments SET active=FALSE WHERE id=?", childId);
            jdbc.update("UPDATE user_accounts SET phone=?, display_title=?, department_id=? WHERE id=?",
                    "0912345678", "Chuyên viên tuyển dụng", childId, employeeId);

            assertThat(jdbc.queryForObject("SELECT parent_id FROM departments WHERE id=?", UUID.class, parentId))
                    .isEqualTo(rootId);
            assertThat(jdbc.queryForObject("SELECT parent_id FROM departments WHERE id=?", UUID.class, childId))
                    .isEqualTo(parentId);
            assertThat(jdbc.queryForObject("SELECT manager_user_id FROM departments WHERE id=?", UUID.class, childId))
                    .isEqualTo(managerId);
            assertThat(jdbc.queryForObject("SELECT active FROM departments WHERE id=?", Boolean.class, rootId)).isTrue();
            assertThat(jdbc.queryForObject("SELECT active FROM departments WHERE id=?", Boolean.class, childId)).isFalse();
            assertThat(jdbc.queryForObject("SELECT created_at FROM departments WHERE id=?", Timestamp.class, childId))
                    .isNotNull();
            assertThat(jdbc.queryForObject("SELECT department_id FROM user_accounts WHERE id=?", UUID.class, employeeId))
                    .isEqualTo(childId);
            assertThat(jdbc.queryForObject("SELECT phone FROM user_accounts WHERE id=?", String.class, employeeId))
                    .isEqualTo("0912345678");
            assertThat(jdbc.queryForObject("SELECT display_title FROM user_accounts WHERE id=?", String.class, employeeId))
                    .isEqualTo("Chuyên viên tuyển dụng");

            assertThatThrownBy(() -> jdbc.update("DELETE FROM departments WHERE id=?", parentId))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("DELETE FROM departments WHERE id=?", childId))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("DELETE FROM user_accounts WHERE id=?", managerId))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("UPDATE user_accounts SET department_id=? WHERE id=?",
                    UUID.randomUUID(), employeeId)).isInstanceOf(DataIntegrityViolationException.class);

            jdbc.update("UPDATE user_accounts SET department_id=NULL WHERE id=?", employeeId);
            assertThat(jdbc.update("DELETE FROM departments WHERE id=?", childId)).isEqualTo(1);
        }
    }

    @Test
    void rejectsInvalidDepartmentIdentifiersParentsAndManagers() throws Exception {
        try (var postgres = startPostgres()) {
            var jdbc = migrateAll(postgres);
            UUID managerId = insertAccount(jdbc, "manager@example.test");
            UUID departmentId = insertDepartment(jdbc, "HR", "Nhân sự", null, managerId);

            assertThatThrownBy(() -> insertDepartment(jdbc, "HR", "Mã trùng", null, managerId))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> insertDepartment(jdbc, "EMPTY_MANAGER", "Thiếu quản lý", null, null))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> insertDepartment(jdbc, "UNKNOWN_MANAGER", "Sai quản lý", null, UUID.randomUUID()))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> insertDepartment(jdbc, "UNKNOWN_PARENT", "Sai phòng ban cha", UUID.randomUUID(), managerId))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("UPDATE departments SET parent_id=? WHERE id=?", departmentId, departmentId))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("UPDATE departments SET active=NULL WHERE id=?", departmentId))
                    .isInstanceOf(DataIntegrityViolationException.class);

            for (String invalidCode : new String[]{"", " ", " HR", "HR "}) {
                assertThatThrownBy(() -> insertDepartment(jdbc, invalidCode, "Phòng ban", null, managerId))
                        .isInstanceOf(DataIntegrityViolationException.class);
            }
            for (String invalidName : new String[]{"", " ", " Nhân sự", "Nhân sự "}) {
                assertThatThrownBy(() -> insertDepartment(jdbc, "INVALID_NAME", invalidName, null, managerId))
                        .isInstanceOf(DataIntegrityViolationException.class);
            }
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

    private static UUID insertDepartment(JdbcTemplate jdbc, String code, String name, UUID parentId, UUID managerId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO departments (id,code,name,parent_id,manager_user_id) VALUES (?,?,?,?,?)",
                id, code, name, parentId, managerId);
        return id;
    }
}
