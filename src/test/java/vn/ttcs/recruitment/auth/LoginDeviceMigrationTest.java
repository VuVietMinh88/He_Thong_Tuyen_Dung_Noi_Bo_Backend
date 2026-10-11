package vn.ttcs.recruitment.auth;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LoginDeviceMigrationTest {
    private static final Timestamp CREATED_AT = Timestamp.from(Instant.parse("2026-10-11T00:00:00Z"));
    private static final Timestamp EXPIRES_AT = Timestamp.from(CREATED_AT.toInstant().plusSeconds(1800));

    @Test
    void upgradesV7ClearingOnlyLegacyLoginLocksAndPreservingAccountsAndSessions() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setPort(0)
                .setServerConfig("listen_addresses", "127.0.0.1").start()) {
            var dataSource = postgres.getPostgresDatabase();
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                    .target("7").load().migrate();
            var jdbc = new JdbcTemplate(dataSource);
            UUID adminId = insertAccount(jdbc, "admin@example.test", true);
            UUID activeId = insertAccount(jdbc, "active@example.test", true);
            UUID adminLockedId = insertAccount(jdbc, "admin-locked@example.test", true);
            UUID disabledId = insertAccount(jdbc, "disabled@example.test", false);

            jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'ADMIN')", adminId);
            jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'RECRUITER')", activeId);
            jdbc.update("UPDATE user_accounts SET failed_login_attempts=5, locked_until=? WHERE id=?",
                    EXPIRES_AT, activeId);
            jdbc.update("""
                    UPDATE user_accounts SET failed_login_attempts=5, locked_until=?,
                        admin_locked_at=?, admin_lock_reason=?, admin_locked_by=? WHERE id=?
                    """, EXPIRES_AT, CREATED_AT, "Khóa bởi quản trị viên", adminId, adminLockedId);
            jdbc.update("UPDATE user_accounts SET failed_login_attempts=2 WHERE id=?", disabledId);
            jdbc.update("UPDATE user_accounts SET phone=?, display_title=? WHERE id=?",
                    "0912345678", "Nhân viên tuyển dụng", activeId);
            jdbc.update("""
                    INSERT INTO auth_sessions (id,user_id,refresh_token_hash,created_at,expires_at)
                    VALUES (?,?,?,?,?)
                    """, UUID.randomUUID(), activeId, "a".repeat(64), CREATED_AT, EXPIRES_AT);
            jdbc.update("""
                    INSERT INTO auth_sessions (id,user_id,refresh_token_hash,created_at,expires_at,revoked_at)
                    VALUES (?,?,?,?,?,?)
                    """, UUID.randomUUID(), adminLockedId, "b".repeat(64), CREATED_AT, EXPIRES_AT, CREATED_AT);

            var previousAccounts = jdbc.queryForList("SELECT * FROM user_accounts ORDER BY id");
            var previousRoles = jdbc.queryForList("SELECT * FROM user_roles ORDER BY user_id, role");
            var previousSessions = jdbc.queryForList("SELECT * FROM auth_sessions ORDER BY id");

            var flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                    .target("17.1").load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            flyway.validate();

            var upgradedAccounts = jdbc.queryForList("SELECT * FROM user_accounts ORDER BY id");
            assertThat(upgradedAccounts).hasSameSizeAs(previousAccounts);
            for (int index = 0; index < previousAccounts.size(); index++) {
                // Every field, including credentials, enabled state and admin lock metadata,
                // must remain identical except the obsolete account-wide login restriction.
                var expected = new HashMap<>(previousAccounts.get(index));
                expected.put("failed_login_attempts", 0);
                expected.put("locked_until", null);
                assertThat(upgradedAccounts.get(index)).isEqualTo(expected);
            }
            assertThat(jdbc.queryForList("SELECT * FROM user_roles ORDER BY user_id, role"))
                    .isEqualTo(previousRoles);
            assertThat(jdbc.queryForList("SELECT * FROM auth_sessions ORDER BY id"))
                    .isEqualTo(previousSessions);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM login_devices", Integer.class)).isZero();
        }
    }

    private static UUID insertAccount(JdbcTemplate jdbc, String email, boolean enabled) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO user_accounts (id,email,full_name,password_hash,enabled,created_at)
                VALUES (?,?,?,?,?,?)
                """, id, email, "Tài khoản kiểm thử", "unchanged-password-hash", enabled, CREATED_AT);
        return id;
    }
}
