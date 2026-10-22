package vn.ttcs.recruitment.auth;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.ttcs.recruitment.config.MigrationLineageConfiguration;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CvContactMigrationTest {
    @ParameterizedTest
    @ValueSource(strings = {"s5", "feedback"})
    void appendsSharedCvTablesAndPreservesEveryAppliedChecksumAndExistingRow(String lineage) throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setPort(0)
                .setServerConfig("listen_addresses", "127.0.0.1").start()) {
            DataSource source = postgres.getPostgresDatabase();
            var configuration = Flyway.configure().dataSource(source);
            new MigrationLineageConfiguration(lineage).customize(configuration);
            // Apply exactly the predecessor scripts available in this branch; never fabricate VPS V56 history.
            configuration.target(lineage.equals("s5") ? "43" : "45").load().migrate();
            var db = new JdbcTemplate(source);
            var history = db.queryForList("SELECT installed_rank,version,script,checksum,success FROM flyway_schema_history ORDER BY installed_rank");
            var grants = db.queryForList("SELECT * FROM role_permissions ORDER BY role_code,permission_code");
            UUID account = UUID.randomUUID();
            db.update("INSERT INTO user_accounts(id,email,full_name,password_hash,created_at) VALUES (?,?,?,?,?)",
                    account, "upgrade@example.test", "Existing account", "test-only", Timestamp.from(Instant.parse("2026-10-21T00:00:00Z")));
            var existing = db.queryForMap("SELECT * FROM user_accounts WHERE id=?", account);

            var upgrade = Flyway.configure().dataSource(source);
            new MigrationLineageConfiguration(lineage).customize(upgrade);
            var flyway = upgrade.load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(2);
            flyway.validate();
            assertThat(db.queryForList("SELECT installed_rank,version,script,checksum,success FROM flyway_schema_history ORDER BY installed_rank"))
                    .startsWith(history.toArray(java.util.Map[]::new));
            assertThat(db.queryForList("SELECT * FROM role_permissions ORDER BY role_code,permission_code")).isEqualTo(grants);
            assertThat(db.queryForMap("SELECT * FROM user_accounts WHERE id=?", account)).isEqualTo(existing);
            assertThat(db.queryForList("SELECT version FROM flyway_schema_history WHERE version IN ('59','60') ORDER BY installed_rank", String.class))
                    .containsExactly("59", "60");
            for (String table : java.util.List.of("cv_extraction_jobs", "cv_extraction_attempts", "application_confirmed_contacts", "application_contact_history")) {
                assertThat(db.queryForObject("SELECT count(*) FROM " + table, Integer.class)).isZero();
            }
            assertThat(db.queryForObject("SELECT count(*) FROM pg_trigger WHERE tgname='application_contact_history_immutable'", Integer.class)).isEqualTo(1);
            assertThat(flyway.migrate().migrationsExecuted).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"s5", "feedback"})
    void aFreshDatabaseInstallsBothSharedMigrations(String lineage) throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setPort(0)
                .setServerConfig("listen_addresses", "127.0.0.1").start()) {
            var configuration = Flyway.configure().dataSource(postgres.getPostgresDatabase());
            new MigrationLineageConfiguration(lineage).customize(configuration);
            var flyway = configuration.load();
            flyway.migrate();
            flyway.validate();
            assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("60");
        }
    }
}
