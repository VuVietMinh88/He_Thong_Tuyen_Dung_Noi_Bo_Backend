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

class AccountLockMigrationTest {
    private static final Timestamp CREATED_AT = Timestamp.from(Instant.parse("2026-10-06T00:00:00Z"));
    private static final Timestamp EXPIRES_AT = Timestamp.from(CREATED_AT.toInstant().plusSeconds(1800));

    @Test
    void upgradesV5WithoutChangingActivationTemporaryLocksCredentialsOrTokens() throws Exception {
        try (var postgres = startPostgres()) {
            var dataSource = postgres.getPostgresDatabase();
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                    .target("5").load().migrate();
            var jdbc = new JdbcTemplate(dataSource);
            UUID activeId = insertAccount(jdbc, "active@example.test", true);
            UUID pendingId = insertAccount(jdbc, "pending@example.test", false);
            UUID disabledId = insertAccount(jdbc, "disabled@example.test", false);
            UUID temporaryId = insertAccount(jdbc, "temporary@example.test", true);
            jdbc.update("UPDATE user_accounts SET failed_login_attempts=5, locked_until=? WHERE id=?",
                    EXPIRES_AT, temporaryId);
            jdbc.update("UPDATE user_accounts SET phone=?, display_title=? WHERE id=?",
                    "0912345678", "Nhân viên tuyển dụng", activeId);
            jdbc.update("INSERT INTO user_roles (user_id,role) VALUES (?, 'ADMIN')", activeId);
            jdbc.update("INSERT INTO user_roles (user_id,role) VALUES (?, 'RECRUITER')", pendingId);
            jdbc.update("INSERT INTO auth_sessions (id,user_id,refresh_token_hash,created_at,expires_at) VALUES (?,?,?,?,?)",
                    UUID.randomUUID(), activeId, "a".repeat(64), CREATED_AT, EXPIRES_AT);
            jdbc.update("INSERT INTO password_reset_tokens (id,user_id,token_hash,created_at,expires_at) VALUES (?,?,?,?,?)",
                    UUID.randomUUID(), activeId, "b".repeat(64), CREATED_AT, EXPIRES_AT);
            jdbc.update("INSERT INTO account_activation_tokens (user_id,token_hash,created_at,expires_at) VALUES (?,?,?,?)",
                    pendingId, "c".repeat(64), CREATED_AT, EXPIRES_AT);

            var previousAccounts = jdbc.queryForList("SELECT * FROM user_accounts ORDER BY id");
            var previousRoles = jdbc.queryForList("SELECT * FROM user_roles ORDER BY user_id,role");
            var previousSessions = jdbc.queryForList("SELECT * FROM auth_sessions ORDER BY id");
            var previousResetTokens = jdbc.queryForList("SELECT * FROM password_reset_tokens ORDER BY id");
            var previousInvitations = jdbc.queryForList("SELECT * FROM account_activation_tokens ORDER BY user_id");

            var flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            flyway.validate();

            var upgradedAccounts = jdbc.queryForList("SELECT * FROM user_accounts ORDER BY id");
            assertThat(upgradedAccounts).hasSameSizeAs(previousAccounts);
            for (int index = 0; index < previousAccounts.size(); index++) {
                assertThat(upgradedAccounts.get(index)).containsAllEntriesOf(previousAccounts.get(index));
                assertThat(upgradedAccounts.get(index)).containsKeys(
                        "admin_locked_at", "admin_lock_reason", "admin_locked_by");
                assertThat(upgradedAccounts.get(index).get("admin_locked_at")).isNull();
                assertThat(upgradedAccounts.get(index).get("admin_lock_reason")).isNull();
                assertThat(upgradedAccounts.get(index).get("admin_locked_by")).isNull();
            }
            assertThat(jdbc.queryForList("SELECT * FROM user_roles ORDER BY user_id,role")).isEqualTo(previousRoles);
            assertThat(jdbc.queryForList("SELECT * FROM auth_sessions ORDER BY id")).isEqualTo(previousSessions);
            assertThat(jdbc.queryForList("SELECT * FROM password_reset_tokens ORDER BY id")).isEqualTo(previousResetTokens);
            assertThat(jdbc.queryForList("SELECT * FROM account_activation_tokens ORDER BY user_id"))
                    .isEqualTo(previousInvitations);
            assertThat(jdbc.queryForObject("SELECT enabled FROM user_accounts WHERE id=?", Boolean.class, disabledId))
                    .isFalse();
        }
    }

    @Test
    void requiresCompleteLockMetadataValidReasonAndAnExistingActor() throws Exception {
        try (var postgres = startPostgres()) {
            var dataSource = postgres.getPostgresDatabase();
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
            var jdbc = new JdbcTemplate(dataSource);
            UUID actorId = insertAccount(jdbc, "admin@example.test", true);
            UUID targetId = insertAccount(jdbc, "target@example.test", true);

            // Every partially populated combination must fail, not merely a missing reason.
            for (int combination = 1; combination < 7; combination++) {
                Timestamp lockedAt = (combination & 1) == 0 ? null : CREATED_AT;
                String reason = (combination & 2) == 0 ? null : "Nhân sự nghỉ việc";
                UUID lockedBy = (combination & 4) == 0 ? null : actorId;
                assertThatThrownBy(() -> setLock(jdbc, targetId, lockedAt, reason, lockedBy))
                        .isInstanceOf(DataIntegrityViolationException.class);
            }
            for (String invalidReason : new String[]{"", " ", "\t", "\n", " đầu", "cuối ", "\tlý do", "lý do\n",
                    "x".repeat(501)}) {
                assertThatThrownBy(() -> setLock(jdbc, targetId, CREATED_AT, invalidReason, actorId))
                        .isInstanceOf(DataIntegrityViolationException.class);
            }
            assertThatThrownBy(() -> setLock(jdbc, targetId, CREATED_AT, "Nhân sự nghỉ việc", UUID.randomUUID()))
                    .isInstanceOf(DataIntegrityViolationException.class);

            assertThat(setLock(jdbc, targetId, CREATED_AT, "Nhân sự nghỉ việc", actorId)).isEqualTo(1);
            var lockedAccount = jdbc.queryForMap("SELECT * FROM user_accounts WHERE id=?", targetId);
            assertThat(lockedAccount.get("admin_lock_reason")).isEqualTo("Nhân sự nghỉ việc");
            assertThat(lockedAccount.get("admin_locked_at")).isEqualTo(CREATED_AT);
            assertThat(lockedAccount.get("admin_locked_by")).isEqualTo(actorId);
            assertThat(lockedAccount.get("enabled")).isEqualTo(true);
            assertThatThrownBy(() -> jdbc.update("DELETE FROM user_accounts WHERE id=?", actorId))
                    .isInstanceOf(DataIntegrityViolationException.class);

            assertThat(setLock(jdbc, targetId, null, null, null)).isEqualTo(1);
            assertThat(jdbc.update("DELETE FROM user_accounts WHERE id=?", actorId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT enabled FROM user_accounts WHERE id=?", Boolean.class, targetId))
                    .isTrue();
        }
    }

    private static EmbeddedPostgres startPostgres() throws Exception {
        return EmbeddedPostgres.builder().setPort(0)
                .setServerConfig("listen_addresses", "127.0.0.1").start();
    }

    private static UUID insertAccount(JdbcTemplate jdbc, String email, boolean enabled) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO user_accounts (id,email,full_name,password_hash,enabled,created_at) VALUES (?,?,?,?,?,?)",
                id, email, "Tài khoản kiểm thử", "unchanged-password-hash", enabled, CREATED_AT);
        return id;
    }

    private static int setLock(JdbcTemplate jdbc, UUID id, Timestamp lockedAt, String reason, UUID lockedBy) {
        return jdbc.update("UPDATE user_accounts SET admin_locked_at=?, admin_lock_reason=?, admin_locked_by=? WHERE id=?",
                lockedAt, reason, lockedBy, id);
    }
}
