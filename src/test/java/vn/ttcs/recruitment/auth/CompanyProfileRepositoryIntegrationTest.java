package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.companyprofile.CompanyMedia;
import vn.ttcs.recruitment.companyprofile.CompanyMediaKind;
import vn.ttcs.recruitment.companyprofile.CompanyMediaRepository;
import vn.ttcs.recruitment.companyprofile.CompanyProfile;
import vn.ttcs.recruitment.companyprofile.CompanyProfileRepository;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Hibernate runs with ddl-auto=validate, so this context only starts when the entities match V11.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CompanyProfileRepositoryIntegrationTest {
    private static final Instant CREATED_AT = Instant.parse("2026-10-07T00:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-10-07T08:30:00Z");
    private static final byte[] PNG_SIGNATURE = HexFormat.of().parseHex("89504e470d0a1a0a");
    private static final byte[] JPEG_SIGNATURE = HexFormat.of().parseHex("ffd8ff");

    @Autowired private CompanyProfileRepository profiles;
    @Autowired private CompanyMediaRepository media;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private UUID editorId;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        String key = Base64.getEncoder().encodeToString(secret);
        registry.add("app.auth.jwt-secret", () -> key);
    }

    @BeforeEach
    void resetCompanyProfile() {
        jdbc.update("DELETE FROM company_profile");
        jdbc.update("DELETE FROM company_media");
        editorId = UUID.randomUUID();
        jdbc.update("INSERT INTO user_accounts (id,email,full_name,password_hash,created_at) VALUES (?,?,?,?,?)",
                editorId, "hr-" + editorId + "@example.test", "Trưởng phòng Nhân sự", "unused-password-hash",
                Timestamp.from(CREATED_AT));
    }

    @Test
    void savesMediaWithBytesAndReloadsThem() {
        byte[] bytes = image(PNG_SIGNATURE, 2048);
        bytes[2047] = 42;
        var logo = media.saveAndFlush(new CompanyMedia(CompanyMediaKind.LOGO, "image/png", bytes, 512, 256,
                editorId, CREATED_AT));

        var row = jdbc.queryForMap("SELECT * FROM company_media WHERE id=?", logo.getId());
        assertThat(row.get("kind")).isEqualTo("LOGO");
        assertThat(row.get("content_type")).isEqualTo("image/png");
        assertThat(row.get("data")).isEqualTo(bytes);
        assertThat(row.get("size_bytes")).isEqualTo(2048);
        assertThat(row.get("width")).isEqualTo(512);
        assertThat(row.get("height")).isEqualTo(256);
        assertThat(row.get("created_at")).isEqualTo(Timestamp.from(CREATED_AT));
        assertThat(row.get("created_by")).isEqualTo(editorId);

        var reloaded = media.findById(logo.getId()).orElseThrow();
        assertThat(reloaded.getKind()).isEqualTo(CompanyMediaKind.LOGO);
        assertThat(reloaded.getContentType()).isEqualTo("image/png");
        assertThat(reloaded.getData()).isEqualTo(bytes);
        assertThat(reloaded.getSizeBytes()).isEqualTo(2048);
        assertThat(reloaded.getWidth()).isEqualTo(512);
        assertThat(reloaded.getHeight()).isEqualTo(256);
        assertThat(reloaded.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(reloaded.getCreatedBy()).isEqualTo(editorId);
    }

    @Test
    void savesProfileWithLogoAndImagesInOrder() {
        UUID logoId = saveMedia(CompanyMediaKind.LOGO);
        UUID officeId = saveMedia(CompanyMediaKind.IMAGE);
        UUID teamId = saveMedia(CompanyMediaKind.IMAGE);
        String introduction = "Chúng tôi xây dựng phần mềm.\n\n- Làm việc linh hoạt\n- Đào tạo hằng năm";
        profiles.saveAndFlush(new CompanyProfile("Công ty TTCS", "Nơi phát triển tài năng", introduction,
                logoId, List.of(teamId, officeId), editorId, CREATED_AT));

        var row = jdbc.queryForMap("SELECT * FROM company_profile");
        assertThat(row.get("id")).isEqualTo(CompanyProfile.SINGLETON_ID);
        assertThat(row.get("company_name")).isEqualTo("Công ty TTCS");
        assertThat(row.get("tagline")).isEqualTo("Nơi phát triển tài năng");
        assertThat(row.get("introduction")).isEqualTo(introduction);
        assertThat(row.get("logo_media_id")).isEqualTo(logoId);
        assertThat(row.get("logo_media_kind")).isEqualTo("LOGO");
        assertThat(row.get("created_at")).isEqualTo(Timestamp.from(CREATED_AT));
        assertThat(row.get("updated_at")).isEqualTo(Timestamp.from(CREATED_AT));
        assertThat(row.get("updated_by")).isEqualTo(editorId);
        assertThat(jdbc.queryForList("""
                SELECT media_id FROM company_profile_images
                WHERE profile_id=1 AND media_kind='IMAGE' ORDER BY display_order
                """, UUID.class)).containsExactly(teamId, officeId);

        var reloaded = profiles.findById(CompanyProfile.SINGLETON_ID).orElseThrow();
        assertThat(reloaded.getCompanyName()).isEqualTo("Công ty TTCS");
        assertThat(reloaded.getTagline()).isEqualTo("Nơi phát triển tài năng");
        assertThat(reloaded.getIntroduction()).isEqualTo(introduction);
        assertThat(reloaded.getLogoMediaId()).isEqualTo(logoId);
        assertThat(reloaded.getImageIds()).containsExactly(teamId, officeId);
        assertThat(reloaded.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(CREATED_AT);
        assertThat(reloaded.getUpdatedBy()).isEqualTo(editorId);
        assertThatThrownBy(() -> reloaded.getImageIds().add(logoId))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void updateReordersRemovesAndAddsImagesInOneTransaction() {
        UUID first = saveMedia(CompanyMediaKind.IMAGE);
        UUID second = saveMedia(CompanyMediaKind.IMAGE);
        UUID third = saveMedia(CompanyMediaKind.IMAGE);
        UUID added = saveMedia(CompanyMediaKind.IMAGE);
        UUID logoId = saveMedia(CompanyMediaKind.LOGO);
        profiles.saveAndFlush(new CompanyProfile("Công ty TTCS", null, "Giới thiệu", null,
                List.of(first, second, third), editorId, CREATED_AT));

        // Swapping the first two images makes Hibernate write a repeated image for a moment;
        // the deferred UNIQUE constraint of V11 only checks the final list at commit.
        updateProfile(List.of(second, first, third), null);
        assertThat(savedImageIds()).containsExactly(second, first, third);

        updateProfile(List.of(third, added), logoId);
        assertThat(savedImageIds()).containsExactly(third, added);
        var reloaded = profiles.findById(CompanyProfile.SINGLETON_ID).orElseThrow();
        assertThat(reloaded.getImageIds()).containsExactly(third, added);
        assertThat(reloaded.getCompanyName()).isEqualTo("Công ty TTCS mới");
        assertThat(reloaded.getTagline()).isEqualTo("Khẩu hiệu mới");
        assertThat(reloaded.getIntroduction()).isEqualTo("Giới thiệu mới");
        assertThat(reloaded.getLogoMediaId()).isEqualTo(logoId);
        assertThat(reloaded.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(UPDATED_AT);

        updateProfile(List.of(), null);
        assertThat(savedImageIds()).isEmpty();
        assertThat(profiles.findById(CompanyProfile.SINGLETON_ID).orElseThrow().getLogoMediaId()).isNull();
    }

    @Test
    void databaseRejectsWrongKindsRepeatedImagesAndTooManyImagesSavedThroughJpa() {
        UUID logoId = saveMedia(CompanyMediaKind.LOGO);
        UUID imageId = saveMedia(CompanyMediaKind.IMAGE);
        List<UUID> elevenImages = new ArrayList<>();
        for (int i = 0; i <= CompanyProfile.MAX_IMAGES; i++) {
            elevenImages.add(saveMedia(CompanyMediaKind.IMAGE));
        }

        assertThatThrownBy(() -> profiles.saveAndFlush(new CompanyProfile("Công ty TTCS", null, "Giới thiệu",
                imageId, List.of(), editorId, CREATED_AT)))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("company_profile_logo_fk");
        assertThatThrownBy(() -> profiles.saveAndFlush(new CompanyProfile("Công ty TTCS", null, "Giới thiệu",
                null, List.of(logoId), editorId, CREATED_AT)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("company_profile_images_media_fk");
        assertThatThrownBy(() -> profiles.saveAndFlush(new CompanyProfile("Công ty TTCS", null, "Giới thiệu",
                null, List.of(imageId, imageId), editorId, CREATED_AT)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("company_profile_images_media_key");
        assertThatThrownBy(() -> profiles.saveAndFlush(new CompanyProfile("Công ty TTCS", null, "Giới thiệu",
                null, elevenImages, editorId, CREATED_AT)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("valid_company_image_order");
        assertThatThrownBy(() -> media.saveAndFlush(new CompanyMedia(CompanyMediaKind.IMAGE, "image/png",
                image(JPEG_SIGNATURE, 16), 100, 100, editorId, CREATED_AT)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("valid_company_media_signature");
        assertThatThrownBy(() -> media.saveAndFlush(new CompanyMedia(CompanyMediaKind.IMAGE, "image/png",
                image(PNG_SIGNATURE, CompanyMedia.MAX_SIZE_BYTES + 1), 100, 100, editorId, CREATED_AT)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("valid_company_media_size");
        assertThatThrownBy(() -> media.saveAndFlush(new CompanyMedia(CompanyMediaKind.IMAGE, "image/png",
                image(PNG_SIGNATURE, 16), CompanyMedia.MAX_DIMENSION + 1, 100, editorId, CREATED_AT)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("valid_company_media_dimensions");

        assertThat(profiles.count()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM company_profile_images", Integer.class)).isZero();
        assertThat(media.count()).isEqualTo(2 + CompanyProfile.MAX_IMAGES + 1);
    }

    @Test
    void repeatedImageInsideTransactionOnlyFailsAtCommitOutsideTheCatch() {
        UUID imageId = saveMedia(CompanyMediaKind.IMAGE);
        AtomicBoolean flushed = new AtomicBoolean();
        AtomicBoolean caughtInside = new AtomicBoolean();

        // Same shape as a @Transactional service method that catches DataIntegrityViolationException:
        // the deferred UNIQUE check of V11 waits for the commit, so the catch never sees the repeat
        // and the error only comes out of the transaction manager. The write API must reject it first.
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            try {
                profiles.saveAndFlush(new CompanyProfile("Công ty TTCS", null, "Giới thiệu", null,
                        List.of(imageId, imageId), editorId, CREATED_AT));
                flushed.set(true);
            } catch (DataIntegrityViolationException exception) {
                caughtInside.set(true);
            }
        })).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("company_profile_images_media_key");

        assertThat(flushed).isTrue();
        assertThat(caughtInside).isFalse();
        assertThat(profiles.count()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM company_profile_images", Integer.class)).isZero();
    }

    private void updateProfile(List<UUID> imageIds, UUID logoId) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            var profile = profiles.findById(CompanyProfile.SINGLETON_ID).orElseThrow();
            profile.update("Công ty TTCS mới", "Khẩu hiệu mới", "Giới thiệu mới", logoId, imageIds, editorId,
                    UPDATED_AT);
        });
    }

    private List<UUID> savedImageIds() {
        return jdbc.queryForList("SELECT media_id FROM company_profile_images ORDER BY display_order", UUID.class);
    }

    private UUID saveMedia(CompanyMediaKind kind) {
        var saved = media.saveAndFlush(new CompanyMedia(kind, "image/jpeg", image(JPEG_SIGNATURE, 32), 100, 100,
                editorId, CREATED_AT));
        return saved.getId();
    }

    // Starts with the PNG or JPEG signature; the rest does not matter to the database.
    private static byte[] image(byte[] signature, int length) {
        byte[] bytes = new byte[length];
        System.arraycopy(signature, 0, bytes, 0, signature.length);
        return bytes;
    }
}
