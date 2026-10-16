package vn.ttcs.recruitment.auth;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Feedback item 7: V39 adds the per-browser forgot-password counter. The table names a browser UUID only, never an
// email, account or IP address, and no existing row changes.
class PasswordResetDeviceMigrationTest {
    private static final MigrationVersion DEVICE_VERSION = MigrationVersion.fromVersion("39");
    private static final Timestamp CREATED_AT = Timestamp.from(Instant.parse("2026-10-16T00:00:00Z"));
    private static final Timestamp LATER = Timestamp.from(Instant.parse("2026-10-16T00:15:00Z"));

    // One database migrated to V39 for the rule tests; each test starts without rows. The target stays V39, so these
    // tests keep checking V39 itself when later migrations come.
    private static EmbeddedPostgres postgres;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void startMigratedDatabase() throws Exception {
        postgres = startPostgres();
        var dataSource = postgres.getPostgresDatabase();
        flyway(dataSource).target(DEVICE_VERSION).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        postgres.close();
    }

    @BeforeEach
    void deleteData() {
        jdbc.update("DELETE FROM password_reset_devices");
    }

    // Accounts, roles, sessions and reset links made before V39 stay as they are.
    @Test
    void theUpgradeChangesNoRow() throws Exception {
        try (var upgradePostgres = startPostgres()) {
            var dataSource = upgradePostgres.getPostgresDatabase();
            flyway(dataSource).target(latestVersionBefore(dataSource, DEVICE_VERSION)).load().migrate();
            var db = new JdbcTemplate(dataSource);
            UUID userId = UUID.randomUUID();
            db.update("INSERT INTO user_accounts (id,email,full_name,password_hash,created_at) VALUES (?,?,?,?,?)",
                    userId, "reset@example.test", "Tài khoản kiểm thử", "unchanged-password-hash", CREATED_AT);
            db.update("INSERT INTO user_roles (user_id,role) VALUES (?, 'RECRUITER')", userId);
            db.update("INSERT INTO auth_sessions (id,user_id,refresh_token_hash,created_at,expires_at) "
                    + "VALUES (?,?,?,?,?)", UUID.randomUUID(), userId, "a".repeat(64), CREATED_AT, LATER);
            db.update("INSERT INTO password_reset_tokens (id,user_id,token_hash,created_at,expires_at) "
                    + "VALUES (?,?,?,?,?)", UUID.randomUUID(), userId, "b".repeat(64), CREATED_AT, LATER);
            Map<String, Object> before = snapshot(db);

            var flyway = flyway(dataSource).target(DEVICE_VERSION).load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            flyway.validate();

            assertThat(snapshot(db)).isEqualTo(before);
            assertThat(db.queryForObject("SELECT count(*) FROM password_reset_devices", Integer.class)).isZero();
        }
    }

    @Test
    void theTableKeepsOnlyTheBrowserCounter() {
        Map<String, String> columns = new HashMap<>();
        jdbc.queryForList("""
                SELECT column_name, data_type, is_nullable FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'password_reset_devices'
                """).forEach(column -> columns.put((String) column.get("column_name"),
                column.get("data_type") + " " + column.get("is_nullable")));
        assertThat(columns).containsOnly(
                Map.entry("id", "uuid NO"),
                Map.entry("request_count", "integer NO"),
                Map.entry("window_started_at", "timestamp with time zone YES"),
                Map.entry("locked_until", "timestamp with time zone YES"),
                Map.entry("updated_at", "timestamp with time zone NO"));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.table_constraints
                WHERE table_name = 'password_reset_devices' AND constraint_type = 'FOREIGN KEY'
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE schemaname = 'public' "
                + "AND tablename = 'password_reset_devices'", String.class))
                .containsExactlyInAnyOrder("password_reset_devices_pkey", "password_reset_devices_updated_at_idx");
    }

    @Test
    void aNewBrowserStartsWithoutRequestsAndTheCounterStopsAtFive() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO password_reset_devices (id, updated_at) VALUES (?, ?)", id, CREATED_AT);
        assertThat(jdbc.queryForMap("SELECT * FROM password_reset_devices WHERE id = ?", id))
                .containsEntry("request_count", 0).containsEntry("window_started_at", null)
                .containsEntry("locked_until", null);
        assertThat(jdbc.update("UPDATE password_reset_devices SET request_count = 4, window_started_at = ? "
                + "WHERE id = ?", CREATED_AT, id)).isEqualTo(1);
        for (int count : new int[]{6, -1}) {
            assertRejected("UPDATE password_reset_devices SET request_count = ? WHERE id = ?",
                    "valid_password_reset_device_request_count", count, id);
        }
        assertThat(jdbc.update("UPDATE password_reset_devices SET request_count = 5, locked_until = ? WHERE id = ?",
                LATER, id)).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT * FROM password_reset_devices WHERE id = ?", id))
                .containsEntry("request_count", 5).containsEntry("window_started_at", CREATED_AT)
                .containsEntry("locked_until", LATER);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO password_reset_devices (id, updated_at) VALUES (?, ?)",
                id, CREATED_AT)).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("password_reset_devices_pkey");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO password_reset_devices (id) VALUES (?)",
                UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("updated_at");
    }

    @Test
    void aCountedRequestAlwaysHasAWindowAndOnlyTheFifthLocks() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO password_reset_devices (id, request_count, window_started_at, updated_at) "
                + "VALUES (?, 1, ?, ?)", id, CREATED_AT, CREATED_AT);
        assertRejected("UPDATE password_reset_devices SET window_started_at = NULL WHERE id = ?",
                "password_reset_device_window_required", id);
        assertRejected("UPDATE password_reset_devices SET request_count = 0 WHERE id = ?",
                "password_reset_device_window_required", id);
        for (int count = 1; count <= 4; count++) {
            assertRejected("UPDATE password_reset_devices SET request_count = ?, locked_until = ? WHERE id = ?",
                    "password_reset_device_lock_after_limit", count, LATER, id);
        }
        assertThat(jdbc.queryForMap("SELECT * FROM password_reset_devices WHERE id = ?", id))
                .containsEntry("request_count", 1).containsEntry("window_started_at", CREATED_AT)
                .containsEntry("locked_until", null);
    }

    private static Map<String, Object> snapshot(JdbcTemplate db) {
        Map<String, Object> rows = new HashMap<>();
        for (String table : new String[]{"user_accounts", "user_roles", "auth_sessions", "password_reset_tokens"}) {
            rows.put(table, db.queryForList("SELECT * FROM " + table + " ORDER BY 1, 2"));
        }
        return rows;
    }

    private static void assertRejected(String sql, String message, Object... arguments) {
        assertThatThrownBy(() -> jdbc.update(sql, arguments))
                .as(message + " " + Arrays.toString(arguments))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(message);
    }

    private static EmbeddedPostgres startPostgres() throws Exception {
        return EmbeddedPostgres.builder().setPort(0).setServerConfig("listen_addresses", "127.0.0.1").start();
    }

    private static FluentConfiguration flyway(DataSource dataSource) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration");
    }

    // The upgrade starts from the newest migration below V39, whichever task added it.
    private static MigrationVersion latestVersionBefore(DataSource dataSource, MigrationVersion version) {
        return Arrays.stream(flyway(dataSource).load().info().all())
                .map(MigrationInfo::getVersion)
                .filter(candidate -> candidate.compareTo(version) < 0)
                .max(Comparator.naturalOrder())
                .orElseThrow();
    }
}
