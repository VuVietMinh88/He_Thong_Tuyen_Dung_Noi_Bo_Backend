package vn.ttcs.recruitment.auth;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// V38: a phone number belongs to at most one account of any status, while any number of accounts may have none. An
// existing database where numbers are already shared stops the upgrade and only says how many numbers are shared.
// Each test migrates to the last version before V38 (whichever other migrations exist then), adds accounts and then
// migrates to V38 only, so these tests keep checking V38 itself when later migrations change the table.
class AccountPhoneUniquenessMigrationTest {
    private static final MigrationVersion PHONE_VERSION = MigrationVersion.fromVersion("38");
    private static final Timestamp CREATED_AT = Timestamp.from(Instant.parse("2026-10-16T00:00:00Z"));

    @Test
    void upgradeKeepsEveryAccountAndThenRefusesASecondAccountWithTheSameNumber() throws Exception {
        try (var postgres = startPostgres()) {
            DataSource dataSource = postgres.getPostgresDatabase();
            flyway(dataSource).target(lastBeforePhoneConstraint(dataSource)).load().migrate();
            var jdbc = new JdbcTemplate(dataSource);
            UUID active = insertAccount(jdbc, "active@example.test", "0912345678", true);
            UUID pending = insertAccount(jdbc, "pending@example.test", "0987654321", false);
            UUID locked = insertAccount(jdbc, "locked@example.test", "02812345678", true);
            jdbc.update("UPDATE user_accounts SET admin_locked_at=?, admin_lock_reason='Nghỉ việc', admin_locked_by=? "
                    + "WHERE id=?", CREATED_AT, active, locked);
            insertAccount(jdbc, "nophone1@example.test", null, true);
            insertAccount(jdbc, "nophone2@example.test", null, false);
            var before = jdbc.queryForList("SELECT * FROM user_accounts ORDER BY id");

            var upgrade = flyway(dataSource).target(PHONE_VERSION).load();
            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
            upgrade.validate();

            assertThat(jdbc.queryForList("SELECT * FROM user_accounts ORDER BY id")).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conname='user_accounts_phone_key' "
                    + "AND contype='u' AND conrelid='user_accounts'::regclass", Integer.class)).isEqualTo(1);
            // Active, pending and locked accounts all keep their number reserved.
            for (String taken : new String[]{"0912345678", "0987654321", "02812345678"}) {
                assertThatThrownBy(() -> insertAccount(jdbc, "second-" + taken + "@example.test", taken, true))
                        .isInstanceOf(DuplicateKeyException.class).hasMessageContaining("user_accounts_phone_key");
            }
            assertThatThrownBy(() -> jdbc.update("UPDATE user_accounts SET phone='0912345678' WHERE id=?", pending))
                    .isInstanceOf(DuplicateKeyException.class).hasMessageContaining("user_accounts_phone_key");
            // NULL is never equal to NULL, so accounts without a phone stay possible in any number.
            insertAccount(jdbc, "nophone3@example.test", null, true);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM user_accounts WHERE phone IS NULL", Integer.class))
                    .isEqualTo(3);
            // A number freed by one account can be given to another.
            jdbc.update("UPDATE user_accounts SET phone=NULL WHERE id=?", active);
            assertThat(jdbc.update("UPDATE user_accounts SET phone='0912345678' WHERE id=?", pending)).isEqualTo(1);
        }
    }

    @Test
    void sharedNumbersStopTheUpgradeWithOnlyTheirCountUntilTheyAreFixed() throws Exception {
        try (var postgres = startPostgres()) {
            DataSource dataSource = postgres.getPostgresDatabase();
            flyway(dataSource).target(lastBeforePhoneConstraint(dataSource)).load().migrate();
            var jdbc = new JdbcTemplate(dataSource);
            UUID first = insertAccount(jdbc, "first@example.test", "0912345678", true);
            insertAccount(jdbc, "second@example.test", "0912345678", false);
            insertAccount(jdbc, "third@example.test", "0987654321", true);
            UUID fourth = insertAccount(jdbc, "fourth@example.test", "0987654321", true);
            insertAccount(jdbc, "fifth@example.test", "0987654321", true);
            insertAccount(jdbc, "unique@example.test", "0351234567", true);
            insertAccount(jdbc, "nophone1@example.test", null, true);
            insertAccount(jdbc, "nophone2@example.test", null, true);
            var before = jdbc.queryForList("SELECT * FROM user_accounts ORDER BY id");

            assertThatThrownBy(() -> flyway(dataSource).target(PHONE_VERSION).load().migrate())
                    .isInstanceOf(FlywayException.class)
                    .hasMessageContaining("V38: 2 so dien thoai dang dung cho nhieu tai khoan")
                    .hasMessageNotContaining("0912345678").hasMessageNotContaining("0987654321")
                    .hasMessageNotContaining("@example.test");

            // Nothing changed: no constraint, no applied V38, every account as before.
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conname='user_accounts_phone_key'",
                    Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='38' AND success",
                    Integer.class)).isZero();
            assertThat(jdbc.queryForList("SELECT * FROM user_accounts ORDER BY id")).isEqualTo(before);

            // Once staff have fixed the accounts, the same migration runs.
            jdbc.update("UPDATE user_accounts SET phone=NULL WHERE id=?", first);
            jdbc.update("UPDATE user_accounts SET phone='0351234568' WHERE id=?", fourth);
            jdbc.update("UPDATE user_accounts SET phone=NULL WHERE email='fifth@example.test'");
            assertThat(flyway(dataSource).target(PHONE_VERSION).load().migrate().migrationsExecuted).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conname='user_accounts_phone_key'",
                    Integer.class)).isEqualTo(1);
        }
    }

    private static FluentConfiguration flyway(DataSource dataSource) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration-feedback");
    }

    private static MigrationVersion lastBeforePhoneConstraint(DataSource dataSource) {
        return Arrays.stream(flyway(dataSource).load().info().all()).map(MigrationInfo::getVersion)
                .filter(version -> version.compareTo(PHONE_VERSION) < 0).max(Comparator.naturalOrder()).orElseThrow();
    }

    private static EmbeddedPostgres startPostgres() throws Exception {
        return EmbeddedPostgres.builder().setPort(0)
                .setServerConfig("listen_addresses", "127.0.0.1").start();
    }

    private static UUID insertAccount(JdbcTemplate jdbc, String email, String phone, boolean enabled) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO user_accounts (id,email,full_name,password_hash,enabled,phone,created_at) "
                + "VALUES (?,?,?,?,?,?,?)", id, email, "Tài khoản kiểm thử", "unchanged-password-hash", enabled, phone,
                CREATED_AT);
        return id;
    }
}
