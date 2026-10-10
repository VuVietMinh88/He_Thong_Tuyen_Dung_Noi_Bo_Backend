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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompanyProfileMigrationTest {
    private static final MigrationVersion COMPANY_PROFILE_VERSION = MigrationVersion.fromVersion("11");
    private static final Timestamp CREATED_AT = Timestamp.from(Instant.parse("2026-10-07T00:00:00Z"));
    private static final byte[] PNG_SIGNATURE = HexFormat.of().parseHex("89504e470d0a1a0a");
    private static final byte[] JPEG_SIGNATURE = HexFormat.of().parseHex("ffd8ff");
    private static final int FIVE_MEGABYTES = 5 * 1024 * 1024;

    // One migrated database shared by the rule tests; each test starts with no company data.
    // The upgrade test starts its own database because it needs the schema before V11.
    private static EmbeddedPostgres postgres;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void startMigratedDatabase() throws Exception {
        postgres = startPostgres();
        jdbc = migrateAll(postgres);
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        postgres.close();
    }

    @BeforeEach
    void deleteCompanyData() {
        jdbc.update("DELETE FROM company_profile");
        jdbc.update("DELETE FROM company_media");
        jdbc.update("DELETE FROM user_accounts");
    }

    @Test
    void upgradesPreviousVersionWithoutChangingExistingData() throws Exception {
        try (var upgradePostgres = startPostgres()) {
            var dataSource = upgradePostgres.getPostgresDatabase();
            MigrationVersion previous = versionBeforeCompanyProfile(dataSource);
            flyway(dataSource).target(previous).load().migrate();
            var db = new JdbcTemplate(dataSource);
            UUID managerId = insertAccount(db, "manager@example.test");
            db.update("INSERT INTO user_roles (user_id,role) VALUES (?, 'HR_MANAGER')", managerId);
            db.update("INSERT INTO departments (id,code,name,manager_user_id) VALUES (?,?,?,?)",
                    UUID.randomUUID(), "HR", "Nhân sự", managerId);
            db.update("""
                    INSERT INTO positions (id,code,name,level,salary_min,salary_max,created_at,updated_at)
                    VALUES (?,?,?,?,?,?,?,?)
                    """, UUID.randomUUID(), "DEV", "Lập trình viên", "Junior", 15_000_000L, 25_000_000L,
                    CREATED_AT, CREATED_AT);
            var previousAccounts = db.queryForList("SELECT * FROM user_accounts ORDER BY id");
            var previousRoles = db.queryForList("SELECT * FROM user_roles ORDER BY user_id,role");
            var previousDepartments = db.queryForList("SELECT * FROM departments ORDER BY id");
            var previousPositions = db.queryForList("SELECT * FROM positions ORDER BY id");
            var previousPermissions = db.queryForList("SELECT * FROM permissions ORDER BY code");
            var previousGrants = db.queryForList(
                    "SELECT * FROM role_permissions ORDER BY role_code,permission_code");

            var flyway = flyway(dataSource).target(COMPANY_PROFILE_VERSION).load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            flyway.validate();

            assertThat(flyway.info().current().getVersion()).isEqualTo(COMPANY_PROFILE_VERSION);
            assertThat(db.queryForList("SELECT * FROM user_accounts ORDER BY id")).isEqualTo(previousAccounts);
            assertThat(db.queryForList("SELECT * FROM user_roles ORDER BY user_id,role")).isEqualTo(previousRoles);
            assertThat(db.queryForList("SELECT * FROM departments ORDER BY id")).isEqualTo(previousDepartments);
            assertThat(db.queryForList("SELECT * FROM positions ORDER BY id")).isEqualTo(previousPositions);
            assertThat(db.queryForList("SELECT * FROM permissions ORDER BY code")).isEqualTo(previousPermissions);
            assertThat(db.queryForList("SELECT * FROM role_permissions ORDER BY role_code,permission_code"))
                    .isEqualTo(previousGrants);
            for (String table : List.of("company_profile", "company_media", "company_profile_images")) {
                assertThat(db.queryForObject("SELECT count(*) FROM " + table, Integer.class)).as(table).isZero();
            }
        }
    }

    @Test
    void storesTextLogoAndOrderedImagesWithTheirBytes() {
        UUID editorId = insertAccount(jdbc, "hr@example.test");
        byte[] logoBytes = png(64);
        UUID logoId = insertMedia(jdbc, "LOGO", "image/png", logoBytes, 400, 200, editorId);
        UUID officeId = insertMedia(jdbc, "IMAGE", "image/jpeg", jpeg(128), 1920, 1080, editorId);
        UUID teamId = insertMedia(jdbc, "IMAGE", "image/png", png(32), 6000, 6000, editorId);
        String introduction = "Chúng tôi là công ty phần mềm.\n\n- Làm việc linh hoạt\n- Đào tạo hằng năm";
        insertProfile(jdbc, "Công ty TTCS", "Nơi phát triển tài năng", introduction, logoId, editorId);
        insertImage(jdbc, 0, teamId);
        insertImage(jdbc, 1, officeId);

        var profile = jdbc.queryForMap("SELECT * FROM company_profile");
        assertThat(profile.get("id")).isEqualTo(1);
        assertThat(profile.get("company_name")).isEqualTo("Công ty TTCS");
        assertThat(profile.get("tagline")).isEqualTo("Nơi phát triển tài năng");
        assertThat(profile.get("introduction")).isEqualTo(introduction);
        assertThat(profile.get("logo_media_id")).isEqualTo(logoId);
        assertThat(profile.get("logo_media_kind")).isEqualTo("LOGO");
        assertThat(profile.get("created_at")).isEqualTo(CREATED_AT);
        assertThat(profile.get("updated_at")).isEqualTo(CREATED_AT);
        assertThat(profile.get("updated_by")).isEqualTo(editorId);
        assertThat(jdbc.queryForList(
                "SELECT media_id FROM company_profile_images ORDER BY display_order", UUID.class))
                .containsExactly(teamId, officeId);
        assertThat(jdbc.queryForList("SELECT DISTINCT media_kind FROM company_profile_images", String.class))
                .containsExactly("IMAGE");

        var logo = jdbc.queryForMap("SELECT * FROM company_media WHERE id=?", logoId);
        assertThat(logo.get("kind")).isEqualTo("LOGO");
        assertThat(logo.get("content_type")).isEqualTo("image/png");
        assertThat(logo.get("data")).isEqualTo(logoBytes);
        assertThat(logo.get("size_bytes")).isEqualTo(64);
        assertThat(logo.get("width")).isEqualTo(400);
        assertThat(logo.get("height")).isEqualTo(200);
        assertThat(logo.get("created_at")).isEqualTo(CREATED_AT);
        assertThat(logo.get("created_by")).isEqualTo(editorId);

        jdbc.update("UPDATE company_profile SET tagline=NULL, logo_media_id=NULL");
        assertThat(jdbc.queryForObject("SELECT tagline IS NULL AND logo_media_id IS NULL FROM company_profile",
                Boolean.class)).isTrue();
    }

    @Test
    void keepsAtMostOneProfileRow() {
        UUID editorId = insertAccount(jdbc, "hr@example.test");
        insertProfile(jdbc, "Công ty TTCS", null, "Giới thiệu", null, editorId);

        assertThatThrownBy(() -> insertProfile(jdbc, "Công ty khác", null, "Giới thiệu", null, editorId))
                .isInstanceOf(DuplicateKeyException.class).hasMessageContaining("company_profile_pkey");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO company_profile (id,company_name,introduction,created_at,updated_at,updated_by)
                VALUES (2,'Công ty khác','Giới thiệu',?,?,?)
                """, CREATED_AT, CREATED_AT, editorId))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("single_company_profile");
        assertThatThrownBy(() -> jdbc.update("UPDATE company_profile SET id=2"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("single_company_profile");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM company_profile", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT company_name FROM company_profile", String.class))
                .isEqualTo("Công ty TTCS");
    }

    @Test
    void rejectsMissingBlankPaddedOrTooLongTexts() {
        UUID editorId = insertAccount(jdbc, "hr@example.test");
        insertProfile(jdbc, "Công ty TTCS", "Khẩu hiệu", "Giới thiệu", null, editorId);

        for (String column : new String[]{"company_name", "introduction", "logo_media_kind", "created_at",
                "updated_at", "updated_by"}) {
            String sql = "UPDATE company_profile SET " + column + "=NULL";
            assertThatThrownBy(() -> jdbc.update(sql)).as(column)
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        for (String invalid : new String[]{"", " ", " TTCS", "TTCS ", "TTCS\n"}) {
            assertThatThrownBy(() -> jdbc.update("UPDATE company_profile SET company_name=?", invalid))
                    .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("valid_company_name");
            assertThatThrownBy(() -> jdbc.update("UPDATE company_profile SET tagline=?", invalid))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("valid_company_tagline");
        }
        for (String invalid : new String[]{"", " ", "\n\t \r\n", "a".repeat(20_001)}) {
            assertThatThrownBy(() -> jdbc.update("UPDATE company_profile SET introduction=?", invalid))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("valid_company_introduction");
        }
        assertThatThrownBy(() -> jdbc.update("UPDATE company_profile SET company_name=?", "N".repeat(256)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE company_profile SET tagline=?", "T".repeat(256)))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Limits are inclusive; Vietnamese letters count as one character each.
        String longestIntroduction = "\n  Ứng viên  \n" + "ệ".repeat(20_000 - 14);
        jdbc.update("UPDATE company_profile SET company_name=?, tagline=?, introduction=?",
                "N".repeat(255), "T".repeat(255), longestIntroduction);
        assertThat(jdbc.queryForObject("SELECT char_length(introduction) FROM company_profile", Integer.class))
                .isEqualTo(20_000);
        assertThat(jdbc.queryForObject("SELECT introduction FROM company_profile", String.class))
                .isEqualTo(longestIntroduction);
    }

    @Test
    void acceptsOnlyLogoMediaAsLogoAndImageMediaInGallery() {
        UUID editorId = insertAccount(jdbc, "hr@example.test");
        UUID logoId = insertMedia(jdbc, "LOGO", "image/png", png(16), 100, 100, editorId);
        UUID imageId = insertMedia(jdbc, "IMAGE", "image/jpeg", jpeg(16), 100, 100, editorId);
        insertProfile(jdbc, "Công ty TTCS", null, "Giới thiệu", null, editorId);

        assertThatThrownBy(() -> jdbc.update("UPDATE company_profile SET logo_media_id=?", imageId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("company_profile_logo_fk");
        assertThatThrownBy(() -> jdbc.update("UPDATE company_profile SET logo_media_id=?", UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("company_profile_logo_fk");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE company_profile SET logo_media_id=?, logo_media_kind='IMAGE'", imageId))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("valid_company_logo_kind");
        assertThatThrownBy(() -> insertImage(jdbc, 0, logoId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("company_profile_images_media_fk");
        assertThatThrownBy(() -> insertImage(jdbc, 0, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("company_profile_images_media_fk");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO company_profile_images (profile_id,display_order,media_id,media_kind)
                VALUES (1,0,?,'LOGO')
                """, logoId))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("valid_company_image_kind");
        // Changing the kind of a picture that is already used would break the rule, so it is refused too.
        jdbc.update("UPDATE company_profile SET logo_media_id=?", logoId);
        assertThatThrownBy(() -> jdbc.update("UPDATE company_media SET kind='IMAGE' WHERE id=?", logoId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("company_profile_logo_fk");

        assertThat(jdbc.queryForObject("SELECT logo_media_id FROM company_profile", UUID.class)).isEqualTo(logoId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM company_profile_images", Integer.class)).isZero();
    }

    @Test
    void keepsGalleryOrderedDistinctAndAtMostTenImages() {
        UUID editorId = insertAccount(jdbc, "hr@example.test");
        insertProfile(jdbc, "Công ty TTCS", null, "Giới thiệu", null, editorId);
        UUID[] images = new UUID[11];
        for (int i = 0; i < images.length; i++) {
            images[i] = insertMedia(jdbc, "IMAGE", "image/png", png(16), 100, 100, editorId);
        }
        for (int order = 0; order < 10; order++) {
            insertImage(jdbc, order, images[order]);
        }

        assertThatThrownBy(() -> insertImage(jdbc, 10, images[10]))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("valid_company_image_order");
        assertThatThrownBy(() -> insertImage(jdbc, -1, images[10]))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("valid_company_image_order");
        assertThatThrownBy(() -> insertImage(jdbc, 3, images[10]))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("company_profile_images_pkey");
        jdbc.update("DELETE FROM company_profile_images WHERE display_order=9");
        assertThatThrownBy(() -> insertImage(jdbc, 9, images[0]))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("company_profile_images_media_key");

        // One statement that swaps the first two images: the UNIQUE check waits until the end.
        jdbc.update("""
                UPDATE company_profile_images
                SET media_id = CASE display_order WHEN 0 THEN ?::uuid ELSE ?::uuid END
                WHERE display_order IN (0, 1)
                """, images[1], images[0]);
        assertThat(jdbc.queryForList(
                "SELECT media_id FROM company_profile_images WHERE display_order < 3 ORDER BY display_order",
                UUID.class)).containsExactly(images[1], images[0], images[2]);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM company_profile_images", Integer.class))
                .isEqualTo(9);
    }

    @Test
    void rejectsMediaWithWrongKindTypeBytesSizeOrDimensions() {
        UUID editorId = insertAccount(jdbc, "hr@example.test");
        byte[] html = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);
        byte[] gif = "GIF89a-not-allowed".getBytes(StandardCharsets.US_ASCII);

        assertMediaRejected(jdbc, "BANNER", "image/png", png(16), 16, 100, 100, editorId,
                "valid_company_media_kind");
        assertMediaRejected(jdbc, "IMAGE", "image/gif", gif, gif.length, 100, 100, editorId,
                "valid_company_media_content_type");
        assertMediaRejected(jdbc, "IMAGE", "image/svg+xml", html, html.length, 100, 100, editorId,
                "valid_company_media_content_type");
        assertMediaRejected(jdbc, "IMAGE", "image/png", html, html.length, 100, 100, editorId,
                "valid_company_media_signature");
        assertMediaRejected(jdbc, "IMAGE", "image/png", jpeg(16), 16, 100, 100, editorId,
                "valid_company_media_signature");
        assertMediaRejected(jdbc, "IMAGE", "image/jpeg", png(16), 16, 100, 100, editorId,
                "valid_company_media_signature");
        assertMediaRejected(jdbc, "IMAGE", "image/png", new byte[0], 0, 100, 100, editorId,
                "valid_company_media_signature");
        assertMediaRejected(jdbc, "IMAGE", "image/png", png(16), 17, 100, 100, editorId,
                "valid_company_media_size");
        assertMediaRejected(jdbc, "IMAGE", "image/png", png(FIVE_MEGABYTES + 1), FIVE_MEGABYTES + 1,
                100, 100, editorId, "valid_company_media_size");
        for (int[] size : new int[][]{{0, 100}, {100, 0}, {6001, 100}, {100, 6001}, {-1, 100}}) {
            assertMediaRejected(jdbc, "IMAGE", "image/png", png(16), 16, size[0], size[1], editorId,
                    "valid_company_media_dimensions");
        }
        assertThatThrownBy(() -> insertMedia(jdbc, "IMAGE", "image/png", png(16), 100, 100,
                UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("created_by");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM company_media", Integer.class)).isZero();

        UUID largestId = insertMedia(jdbc, "IMAGE", "image/jpeg", jpeg(FIVE_MEGABYTES), 6000, 6000, editorId);
        UUID smallestId = insertMedia(jdbc, "LOGO", "image/jpeg", jpeg(3), 1, 1, editorId);
        assertThat(jdbc.queryForObject("SELECT size_bytes FROM company_media WHERE id=?", Integer.class, largestId))
                .isEqualTo(FIVE_MEGABYTES);
        assertThat(jdbc.queryForObject("SELECT octet_length(data) FROM company_media WHERE id=?", Integer.class,
                smallestId)).isEqualTo(3);
        for (String column : new String[]{"kind", "content_type", "data", "size_bytes", "width", "height",
                "created_at", "created_by"}) {
            String sql = "UPDATE company_media SET " + column + "=NULL WHERE id=?";
            assertThatThrownBy(() -> jdbc.update(sql, smallestId)).as(column)
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Test
    void protectsUsedMediaAndAccountsAndDeletesGalleryWithProfile() {
        UUID editorId = insertAccount(jdbc, "hr@example.test");
        UUID uploaderId = insertAccount(jdbc, "uploader@example.test");
        UUID logoId = insertMedia(jdbc, "LOGO", "image/png", png(16), 100, 100, uploaderId);
        UUID imageId = insertMedia(jdbc, "IMAGE", "image/png", png(16), 100, 100, uploaderId);
        UUID unusedId = insertMedia(jdbc, "IMAGE", "image/jpeg", jpeg(16), 100, 100, uploaderId);
        insertProfile(jdbc, "Công ty TTCS", null, "Giới thiệu", logoId, editorId);
        insertImage(jdbc, 0, imageId);

        assertThatThrownBy(() -> jdbc.update("DELETE FROM company_media WHERE id=?", logoId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("company_profile_logo_fk");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM company_media WHERE id=?", imageId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("company_profile_images_media_fk");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM user_accounts WHERE id=?", editorId))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("updated_by");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM user_accounts WHERE id=?", uploaderId))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("created_by");
        assertThat(jdbc.update("DELETE FROM company_media WHERE id=?", unusedId)).isEqualTo(1);

        assertThat(jdbc.update("DELETE FROM company_profile")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM company_profile_images", Integer.class)).isZero();
        assertThat(jdbc.queryForList("SELECT id FROM company_media", UUID.class))
                .containsExactlyInAnyOrder(logoId, imageId);
    }

    private static EmbeddedPostgres startPostgres() throws Exception {
        return EmbeddedPostgres.builder().setPort(0)
                .setServerConfig("listen_addresses", "127.0.0.1").start();
    }

    private static FluentConfiguration flyway(DataSource dataSource) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration");
    }

    // Migrations of other tasks may come between V7 and V11. Stopping at whichever one is right
    // before V11 keeps the upgrade test about the changes made by V11 alone.
    private static MigrationVersion versionBeforeCompanyProfile(DataSource dataSource) {
        return Arrays.stream(flyway(dataSource).load().info().all())
                .map(MigrationInfo::getVersion)
                .filter(version -> version.compareTo(COMPANY_PROFILE_VERSION) < 0)
                .max(Comparator.naturalOrder())
                .orElseThrow();
    }

    private static JdbcTemplate migrateAll(EmbeddedPostgres database) {
        var dataSource = database.getPostgresDatabase();
        flyway(dataSource).load().migrate();
        return new JdbcTemplate(dataSource);
    }

    private static UUID insertAccount(JdbcTemplate jdbc, String email) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO user_accounts (id,email,full_name,password_hash,created_at) VALUES (?,?,?,?,?)",
                id, email, "Tài khoản kiểm thử", "unchanged-password-hash", CREATED_AT);
        return id;
    }

    private static UUID insertMedia(JdbcTemplate jdbc, String kind, String contentType, byte[] data,
                                    int width, int height, UUID createdBy) {
        return insertMedia(jdbc, kind, contentType, data, data.length, width, height, createdBy);
    }

    private static UUID insertMedia(JdbcTemplate jdbc, String kind, String contentType, byte[] data, int sizeBytes,
                                    int width, int height, UUID createdBy) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO company_media (id,kind,content_type,data,size_bytes,width,height,created_at,created_by)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, id, kind, contentType, data, sizeBytes, width, height, CREATED_AT, createdBy);
        return id;
    }

    private static void assertMediaRejected(JdbcTemplate jdbc, String kind, String contentType, byte[] data,
                                            int sizeBytes, int width, int height, UUID createdBy,
                                            String constraint) {
        assertThatThrownBy(() -> insertMedia(jdbc, kind, contentType, data, sizeBytes, width, height, createdBy))
                .as(constraint).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining(constraint);
    }

    private static void insertProfile(JdbcTemplate jdbc, String companyName, String tagline, String introduction,
                                      UUID logoMediaId, UUID updatedBy) {
        jdbc.update("""
                INSERT INTO company_profile
                    (company_name,tagline,introduction,logo_media_id,created_at,updated_at,updated_by)
                VALUES (?,?,?,?,?,?,?)
                """, companyName, tagline, introduction, logoMediaId, CREATED_AT, CREATED_AT, updatedBy);
    }

    private static void insertImage(JdbcTemplate jdbc, int displayOrder, UUID mediaId) {
        jdbc.update("INSERT INTO company_profile_images (profile_id,display_order,media_id) VALUES (1,?,?)",
                displayOrder, mediaId);
    }

    // Starts with the PNG signature; the rest does not matter to the db.
    private static byte[] png(int length) {
        byte[] bytes = new byte[length];
        System.arraycopy(PNG_SIGNATURE, 0, bytes, 0, PNG_SIGNATURE.length);
        return bytes;
    }

    private static byte[] jpeg(int length) {
        byte[] bytes = new byte[length];
        System.arraycopy(JPEG_SIGNATURE, 0, bytes, 0, JPEG_SIGNATURE.length);
        return bytes;
    }
}
