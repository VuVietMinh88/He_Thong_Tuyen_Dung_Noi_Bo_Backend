package vn.ttcs.recruitment.auth;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordResetMigrationTest {

    @Test
    void upgradesV1WithoutChangingExistingAccountsOrSessions() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setPort(0)
                .setServerConfig("listen_addresses", "127.0.0.1").start()) {
            var dataSource = postgres.getPostgresDatabase();
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                    .target("1").load().migrate();
            var jdbc = new JdbcTemplate(dataSource);
            UUID userId = UUID.randomUUID();
            UUID sessionId = UUID.randomUUID();
            Timestamp createdAt = Timestamp.from(Instant.parse("2026-10-04T00:00:00Z"));
            Timestamp expiresAt = Timestamp.from(createdAt.toInstant().plusSeconds(1800));
            jdbc.update("INSERT INTO user_accounts (id,email,full_name,password_hash,created_at) VALUES (?,?,?,?,?)",
                    userId, "migration@example.test", "Tài khoản trước migration", "test-hash", createdAt);
            jdbc.update("INSERT INTO user_roles (user_id,role) VALUES (?, 'ADMIN')", userId);
            jdbc.update("INSERT INTO auth_sessions (id,user_id,refresh_token_hash,created_at,expires_at) VALUES (?,?,?,?,?)",
                    sessionId, userId, "b".repeat(64), createdAt, expiresAt);

            var flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                    .target("6").load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(5);
            flyway.validate();
            assertThat(jdbc.queryForObject("SELECT password_hash FROM user_accounts WHERE id=?", String.class, userId))
                    .isEqualTo("test-hash");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_sessions WHERE id=?", Integer.class, sessionId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT role FROM user_roles WHERE user_id=?", String.class, userId)).isEqualTo("ADMIN");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM roles", Integer.class)).isEqualTo(7);
            assertThat(jdbc.queryForObject("SELECT internal FROM roles WHERE code='CANDIDATE'", Boolean.class)).isFalse();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM role_permissions WHERE role_code='ADMIN'",
                    Integer.class)).isGreaterThan(0);
            jdbc.update("INSERT INTO password_reset_tokens (id,user_id,token_hash,created_at,expires_at) VALUES (?,?,?,?,?)",
                    UUID.randomUUID(), userId, "a".repeat(64), createdAt, expiresAt);
            jdbc.update("INSERT INTO account_activation_tokens (user_id,token_hash,created_at,expires_at) VALUES (?,?,?,?)",
                    userId, "c".repeat(64), createdAt, expiresAt);
            jdbc.update("DELETE FROM user_accounts WHERE id=?", userId);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM password_reset_tokens", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM account_activation_tokens", Integer.class)).isZero();
        }
    }
}
