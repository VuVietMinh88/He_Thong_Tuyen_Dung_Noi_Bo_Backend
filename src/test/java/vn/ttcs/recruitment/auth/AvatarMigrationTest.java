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

class AvatarMigrationTest {
    private static final Timestamp CREATED_AT = Timestamp.from(Instant.parse("2026-10-07T00:00:00Z"));
    private static final byte[] IMAGE = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
    private static final byte[] THUMBNAIL = {(byte) 0x89, 'P', 'N', 'G', 4};

    @Test
    void upgradeFromV7AddsAnEmptyAvatarTableWithoutChangingAccounts() throws Exception {
        try (var postgres = startPostgres()) {
            var dataSource = postgres.getPostgresDatabase();
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                    .target("7").load().migrate();
            var jdbc = new JdbcTemplate(dataSource);
            UUID accountId = insertAccount(jdbc, "nhan-vien@example.test");
            jdbc.update("INSERT INTO user_roles (user_id,role) VALUES (?, 'RECRUITER')", accountId);
            // Only V1 columns are compared, so later migrations that add account columns do not break this test.
            String accountColumns = "SELECT id,email,full_name,password_hash,enabled,created_at FROM user_accounts ORDER BY id";
            var previousAccounts = jdbc.queryForList(accountColumns);
            var previousRoles = jdbc.queryForList("SELECT * FROM user_roles ORDER BY user_id,role");

            var flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                    .target("12").load();
            flyway.migrate();
            flyway.validate();

            assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("12");
            assertThat(jdbc.queryForList(accountColumns)).isEqualTo(previousAccounts);
            assertThat(jdbc.queryForList("SELECT * FROM user_roles ORDER BY user_id,role")).isEqualTo(previousRoles);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM user_avatars", Integer.class)).isZero();
        }
    }

    @Test
    void keepsOneAvatarPerAccountAndDeletesItWithTheAccount() throws Exception {
        try (var postgres = startPostgres()) {
            var jdbc = migrateAll(postgres);
            UUID ownerId = insertAccount(jdbc, "co-anh@example.test");
            UUID otherId = insertAccount(jdbc, "khac@example.test");
            insertAvatar(jdbc, ownerId, "image/png", IMAGE, THUMBNAIL, IMAGE.length);
            insertAvatar(jdbc, otherId, "image/jpeg", IMAGE, THUMBNAIL, IMAGE.length);

            var row = jdbc.queryForMap("SELECT * FROM user_avatars WHERE user_id=?", ownerId);
            assertThat(row.get("content_type")).isEqualTo("image/png");
            assertThat((byte[]) row.get("image")).isEqualTo(IMAGE);
            assertThat((byte[]) row.get("thumbnail")).isEqualTo(THUMBNAIL);
            assertThat(row.get("size_bytes")).isEqualTo(IMAGE.length);
            assertThat(row.get("updated_at")).isEqualTo(CREATED_AT);

            assertThatThrownBy(() -> insertAvatar(jdbc, ownerId, "image/png", IMAGE, THUMBNAIL, IMAGE.length))
                    .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("user_avatars_pkey");

            jdbc.update("DELETE FROM user_accounts WHERE id=?", ownerId);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM user_avatars WHERE user_id=?", Integer.class, ownerId))
                    .isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM user_avatars WHERE user_id=?", Integer.class, otherId))
                    .isEqualTo(1);
        }
    }

    @Test
    void rejectsUnknownAccountsUnsupportedTypesWrongSizesAndMissingValues() throws Exception {
        try (var postgres = startPostgres()) {
            var jdbc = migrateAll(postgres);
            UUID ownerId = insertAccount(jdbc, "co-anh@example.test");
            UUID emptyId = insertAccount(jdbc, "chua-co-anh@example.test");
            insertAvatar(jdbc, ownerId, "image/png", IMAGE, THUMBNAIL, IMAGE.length);

            assertThatThrownBy(() -> insertAvatar(jdbc, UUID.randomUUID(), "image/png", IMAGE, THUMBNAIL, IMAGE.length))
                    .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("user_avatars_user_id_fkey");
            for (String type : new String[]{"image/gif", "IMAGE/PNG", "", "text/html"}) {
                assertThatThrownBy(() -> insertAvatar(jdbc, emptyId, type, IMAGE, THUMBNAIL, IMAGE.length))
                        .as(type).isInstanceOf(DataIntegrityViolationException.class)
                        .hasMessageContaining("valid_user_avatar_content_type");
            }
            assertThatThrownBy(() -> insertAvatar(jdbc, emptyId, "image/png", IMAGE, THUMBNAIL, IMAGE.length - 1))
                    .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("valid_user_avatar_size");
            assertThatThrownBy(() -> insertAvatar(jdbc, emptyId, "image/png", new byte[0], THUMBNAIL, 0))
                    .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("valid_user_avatar_size");
            assertThatThrownBy(() -> insertAvatar(jdbc, emptyId, "image/png", IMAGE, new byte[0], IMAGE.length))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("non_empty_user_avatar_thumbnail");
            assertThatThrownBy(() -> jdbc.update("UPDATE user_avatars SET image=? WHERE user_id=?", THUMBNAIL, ownerId))
                    .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("valid_user_avatar_size");
            for (String column : new String[]{"content_type", "image", "thumbnail", "size_bytes", "updated_at"}) {
                String sql = "UPDATE user_avatars SET " + column + "=NULL WHERE user_id=?";
                assertThatThrownBy(() -> jdbc.update(sql, ownerId))
                        .as(column).isInstanceOf(DataIntegrityViolationException.class);
            }

            assertThat(jdbc.queryForObject("SELECT count(*) FROM user_avatars", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT image FROM user_avatars WHERE user_id=?", byte[].class, ownerId))
                    .isEqualTo(IMAGE);
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

    private static void insertAvatar(JdbcTemplate jdbc, UUID userId, String contentType, byte[] image,
                                     byte[] thumbnail, int sizeBytes) {
        jdbc.update("""
                INSERT INTO user_avatars (user_id,content_type,image,thumbnail,size_bytes,updated_at)
                VALUES (?,?,?,?,?,?)
                """, userId, contentType, image, thumbnail, sizeBytes, CREATED_AT);
    }
}
