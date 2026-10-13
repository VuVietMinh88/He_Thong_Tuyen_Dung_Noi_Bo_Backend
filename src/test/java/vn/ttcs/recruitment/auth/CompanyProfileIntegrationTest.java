package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.account.BootstrapAdmin;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.companyprofile.CompanyMedia;
import vn.ttcs.recruitment.companyprofile.CompanyMediaKind;
import vn.ttcs.recruitment.companyprofile.CompanyMediaRepository;
import vn.ttcs.recruitment.companyprofile.CompanyProfileService;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CompanyProfileIntegrationTest {
    private static final String EDITOR = "/api/v1/company-profile";
    private static final String PREVIEW = "/api/v1/company-profile/preview";
    private static final String PUBLIC = "/api/v1/public/company-profile";
    private static final String PASSWORD = "TestingOnly123!";
    private static final Instant START = Instant.parse("2026-10-07T00:00:00Z");
    private static final byte[] PNG_SIGNATURE = HexFormat.of().parseHex("89504e470d0a1a0a");

    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private AccountRepository accounts;
    @Autowired private CompanyMediaRepository media;
    @Autowired private BootstrapAdmin bootstrap;
    @Autowired private AuthIntegrationTest.MutableClock clock;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private UUID adminId;
    private String adminToken;
    private String fixturePasswordHash;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        String key = Base64.getEncoder().encodeToString(secret);
        registry.add("app.auth.jwt-secret", () -> key);
    }

    @BeforeEach
    void resetFixture() throws Exception {
        clock.set(START);
        // The page and the pictures reference user accounts, so they are removed first.
        jdbc.update("DELETE FROM company_profile");
        jdbc.update("DELETE FROM company_media");
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("UPDATE user_accounts SET admin_locked_at = NULL, admin_lock_reason = NULL, admin_locked_by = NULL");
        jdbc.update("DELETE FROM user_accounts");
        bootstrap.run(new DefaultApplicationArguments());
        JsonNode login = login("admin@example.test");
        adminId = UUID.fromString(login.path("user").path("id").asText());
        adminToken = login.path("accessToken").asText();
        fixturePasswordHash = accounts.findById(adminId).orElseThrow().getPasswordHash();
    }

    @Test
    void firstSaveCreatesThePageThatTheEditorAndThePublicPortalRead() throws Exception {
        var editorBefore = get(EDITOR, adminToken);
        error(editorBefore, 404, "COMPANY_PROFILE_NOT_FOUND");
        noStore(editorBefore);
        var publicBefore = get(PUBLIC, null);
        error(publicBefore, 404, "COMPANY_PROFILE_NOT_FOUND");
        noStore(publicBefore);

        UUID logo = picture(CompanyMediaKind.LOGO, 400, 200);
        UUID office = picture(CompanyMediaKind.IMAGE, 1200, 800);
        UUID team = picture(CompanyMediaKind.IMAGE, 800, 600);
        // Spaces around the texts (also a non-breaking space) are removed; Windows line breaks become \n.
        var response = save(payload("  Công ty TTCS\u00a0", "  Nơi phát triển tài năng  ",
                "\r\n  Chúng tôi xây dựng phần mềm.\r\n\r\n- Làm việc linh hoạt\r- Đào tạo hằng năm\n\n",
                logo, List.of(team, office)), adminToken);
        JsonNode saved = expect(response, 200);
        noStore(response);
        String introduction = "Chúng tôi xây dựng phần mềm.\n\n- Làm việc linh hoạt\n- Đào tạo hằng năm";
        assertThat(fieldNames(saved)).containsExactlyInAnyOrder("companyName", "tagline", "introduction",
                "logoMediaId", "imageIds", "createdAt", "updatedAt", "updatedBy");
        assertThat(saved.path("companyName").asText()).isEqualTo("Công ty TTCS");
        assertThat(saved.path("tagline").asText()).isEqualTo("Nơi phát triển tài năng");
        assertThat(saved.path("introduction").asText()).isEqualTo(introduction);
        assertThat(saved.path("logoMediaId").asText()).isEqualTo(logo.toString());
        assertThat(texts(saved.path("imageIds"))).containsExactly(team.toString(), office.toString());
        assertThat(Instant.parse(saved.path("createdAt").asText())).isEqualTo(START);
        assertThat(Instant.parse(saved.path("updatedAt").asText())).isEqualTo(START);
        assertThat(saved.path("updatedBy").asText()).isEqualTo(adminId.toString());

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM company_profile");
        assertThat(row.get("id")).isEqualTo(1);
        assertThat(row.get("company_name")).isEqualTo("Công ty TTCS");
        assertThat(row.get("tagline")).isEqualTo("Nơi phát triển tài năng");
        assertThat(row.get("introduction")).isEqualTo(introduction);
        assertThat(row.get("logo_media_id")).isEqualTo(logo);
        assertThat(row.get("updated_by")).isEqualTo(adminId);
        assertThat(savedImageIds()).containsExactly(team, office);

        assertThat(expect(get(EDITOR, adminToken), 200)).isEqualTo(saved);

        // Candidates need no token and only get the published content, never who edited it or when.
        var publicResponse = get(PUBLIC, null);
        JsonNode page = expect(publicResponse, 200);
        noStore(publicResponse);
        assertThat(fieldNames(page)).containsExactlyInAnyOrder("companyName", "tagline", "introduction", "logo", "images");
        assertThat(page.path("companyName").asText()).isEqualTo("Công ty TTCS");
        assertThat(page.path("tagline").asText()).isEqualTo("Nơi phát triển tài năng");
        assertThat(page.path("introduction").asText()).isEqualTo(introduction);
        assertThat(fieldNames(page.path("logo"))).containsExactlyInAnyOrder("id", "width", "height", "url");
        assertPicture(page.path("logo"), logo, 400, 200);
        assertThat(page.path("images").size()).isEqualTo(2);
        assertPicture(page.path("images").get(0), team, 800, 600);
        assertPicture(page.path("images").get(1), office, 1200, 800);
    }

    @Test
    void previewReturnsExactlyThePublicPageButNeverSavesIt() throws Exception {
        UUID logo = picture(CompanyMediaKind.LOGO, 300, 300);
        UUID image = picture(CompanyMediaKind.IMAGE, 640, 480);
        Map<String, Object> content = payload("Công ty TTCS", null, "Giới thiệu\n\nĐoạn hai", logo, List.of(image));

        var previewResponse = preview(content, adminToken);
        JsonNode preview = expect(previewResponse, 200);
        noStore(previewResponse);
        assertThat(fieldNames(preview)).containsExactlyInAnyOrder("companyName", "tagline", "introduction", "logo", "images");
        assertThat(preview.path("tagline").isNull()).isTrue();
        assertPicture(preview.path("logo"), logo, 300, 300);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM company_profile", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM company_profile_images", Integer.class)).isZero();
        error(get(PUBLIC, null), 404, "COMPANY_PROFILE_NOT_FOUND");
        error(get(EDITOR, adminToken), 404, "COMPANY_PROFILE_NOT_FOUND");

        expect(save(content, adminToken), 200);
        assertThat(expect(get(PUBLIC, null), 200)).isEqualTo(preview);

        // Previewing other content later leaves the saved page and the public portal unchanged.
        Map<String, Object> savedRow = jdbc.queryForMap("SELECT * FROM company_profile");
        clock.set(START.plusSeconds(30));
        JsonNode draft = expect(preview(payload("Tên mới", "Khẩu hiệu mới", "Nội dung mới", null, List.of()),
                adminToken), 200);
        assertThat(draft.path("companyName").asText()).isEqualTo("Tên mới");
        assertThat(draft.path("logo").isNull()).isTrue();
        assertThat(draft.path("images").isEmpty()).isTrue();
        assertThat(jdbc.queryForMap("SELECT * FROM company_profile")).isEqualTo(savedRow);
        assertThat(savedImageIds()).containsExactly(image);
        assertThat(expect(get(PUBLIC, null), 200)).isEqualTo(preview);
    }

    @Test
    void laterSavesReplaceTheWholeContentKeepTheCreationTimeAndRecordTheEditor() throws Exception {
        UUID logo = picture(CompanyMediaKind.LOGO, 100, 100);
        UUID first = picture(CompanyMediaKind.IMAGE, 100, 100);
        UUID second = picture(CompanyMediaKind.IMAGE, 200, 100);
        UUID third = picture(CompanyMediaKind.IMAGE, 300, 100);
        expect(save(payload("Công ty TTCS", "Khẩu hiệu", "Giới thiệu", logo, List.of(first, second, third)),
                adminToken), 200);

        // Swapping two images briefly repeats one row inside the transaction; V11 only checks at commit.
        expect(save(payload("Công ty TTCS", "Khẩu hiệu", "Giới thiệu", logo, List.of(second, first, third)),
                adminToken), 200);
        assertThat(savedImageIds()).containsExactly(second, first, third);

        UUID hrManager = account("hr@example.test", Set.of(Role.HR_MANAGER));
        String hrToken = login("hr@example.test").path("accessToken").asText();
        // The clock has nanoseconds; the response must show the microseconds PostgreSQL actually stores.
        clock.set(START.plusSeconds(60).plusNanos(123_456_789));
        Map<String, Object> replacement = new LinkedHashMap<>();
        replacement.put("companyName", "Công ty TTCS mới");
        replacement.put("tagline", "   ");
        replacement.put("introduction", "Nội dung mới");
        replacement.put("imageIds", List.of(third, second));
        JsonNode updated = expect(save(replacement, hrToken), 200);
        assertThat(updated.path("companyName").asText()).isEqualTo("Công ty TTCS mới");
        assertThat(updated.path("tagline").isNull()).isTrue();
        assertThat(updated.path("logoMediaId").isNull()).isTrue();
        assertThat(texts(updated.path("imageIds"))).containsExactly(third.toString(), second.toString());
        assertThat(Instant.parse(updated.path("createdAt").asText())).isEqualTo(START);
        assertThat(Instant.parse(updated.path("updatedAt").asText()))
                .isEqualTo(START.plusSeconds(60).plusNanos(123_456_000));
        assertThat(updated.path("updatedBy").asText()).isEqualTo(hrManager.toString());
        assertThat(expect(get(EDITOR, adminToken), 200)).isEqualTo(updated);

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM company_profile");
        assertThat(row.get("tagline")).isNull();
        assertThat(row.get("logo_media_id")).isNull();
        assertThat(row.get("updated_by")).isEqualTo(hrManager);
        assertThat(savedImageIds()).containsExactly(third, second);
        JsonNode page = expect(get(PUBLIC, null), 200);
        assertThat(page.path("tagline").isNull()).isTrue();
        assertThat(page.path("logo").isNull()).isTrue();
        assertPicture(page.path("images").get(0), third, 300, 100);
        assertPicture(page.path("images").get(1), second, 200, 100);

        // Leaving imageIds out (or sending null) removes every image; the pictures themselves stay uploaded.
        replacement.remove("imageIds");
        assertThat(expect(save(replacement, hrToken), 200).path("imageIds").isEmpty()).isTrue();
        assertThat(savedImageIds()).isEmpty();
        assertThat(media.count()).isEqualTo(4);
    }

    @Test
    void rejectsInvalidTextAndImageListsForSaveAndPreviewWithoutChangingThePage() throws Exception {
        UUID image = picture(CompanyMediaKind.IMAGE, 100, 100);
        expect(save(payload("Công ty TTCS", null, "Giới thiệu", null, List.of(image)), adminToken), 200);
        Map<String, Object> rowBefore = jdbc.queryForMap("SELECT * FROM company_profile");
        Map<String, Object> valid = payload("Công ty", "Khẩu hiệu", "Nội dung", null, List.of());

        List<UUID> elevenImages = new ArrayList<>();
        for (int index = 0; index < 11; index++) {
            elevenImages.add(picture(CompanyMediaKind.IMAGE, 10, 10));
        }
        List<UUID> withNull = new ArrayList<>();
        withNull.add(image);
        withNull.add(null);
        List<Invalid> cases = List.of(
                new Invalid("companyName", null),
                new Invalid("companyName", " \t\u00a0 "),
                new Invalid("companyName", "x".repeat(256)),
                new Invalid("companyName", "Công ty <b>TTCS</b>"),
                new Invalid("companyName", "Công ty\nTTCS"),
                new Invalid("companyName", "Công ty\tTTCS"),
                // Unicode line separator, bidi override/isolate and a name made only of invisible characters.
                new Invalid("companyName", "Công ty TTCS"),
                new Invalid("companyName", "Công ty ‮SCTT"),
                new Invalid("companyName", "Công ty ⁦TTCS⁩"),
                new Invalid("companyName", "​⁠﻿"),
                new Invalid("tagline", "x".repeat(256)),
                new Invalid("tagline", "<script>alert(1)</script>"),
                new Invalid("tagline", "Dòng một\nDòng hai"),
                new Invalid("tagline", "Dòng một Dòng hai"),
                new Invalid("tagline", "​"),
                new Invalid("introduction", null),
                new Invalid("introduction", " \n\u3000\r\n "),
                new Invalid("introduction", "x".repeat(20_001)),
                new Invalid("introduction", "Xin chào <img src=x onerror=alert(1)>"),
                new Invalid("introduction", "Đoạn cuối</p>"),
                new Invalid("introduction", "Ẩn <!-- ghi chú -->"),
                new Invalid("introduction", "<?xml version=\"1.0\"?>"),
                new Invalid("introduction", "<A HREF=\"javascript:alert(1)\">Bấm</A>"),
                new Invalid("introduction", "Có ký tự NUL\u0000ở giữa"),
                new Invalid("introduction", "Dòng một Dòng hai"),
                new Invalid("introduction", "Giá ‭từ trái sang phải‬"),
                new Invalid("introduction", "​\n\t⁠"),
                new Invalid("imageIds", elevenImages),
                new Invalid("imageIds", List.of(image, image)),
                new Invalid("imageIds", withNull));
        for (Invalid invalid : cases) {
            Map<String, Object> body = new LinkedHashMap<>(valid);
            body.put(invalid.field(), invalid.value());
            for (var response : List.of(save(body, adminToken), preview(body, adminToken))) {
                JsonNode result = expect(response, 400);
                assertThat(result.path("code").asText()).as(invalid.toString()).isEqualTo("VALIDATION_ERROR");
                assertThat(fieldNames(result.path("fieldErrors"))).as(invalid.toString())
                        .anyMatch(name -> name.startsWith(invalid.field()));
            }
        }

        // An invisible name gets a message that says why, not only which field is wrong.
        Map<String, Object> invisibleName = new LinkedHashMap<>(valid);
        invisibleName.put("companyName", "​");
        assertThat(expect(preview(invisibleName, adminToken), 400).path("fieldErrors").path("companyName").asText())
                .contains("ký tự nhìn thấy được");

        // Raw JSON text: half of an emoji (a lone UTF-16 surrogate) can only arrive as a JSON escape.
        String brokenEmoji = "{\"companyName\":\"Công ty\",\"introduction\":\"Emoji \\ud83d bị cắt\"}";
        for (String path : List.of(EDITOR, PREVIEW)) {
            JsonNode result = expect(request(path.equals(EDITOR) ? "PUT" : "POST", path, brokenEmoji, adminToken), 400);
            assertThat(result.path("code").asText()).isEqualTo("VALIDATION_ERROR");
            assertThat(result.path("fieldErrors").has("introduction")).isTrue();
        }
        for (String malformed : List.of(
                "{\"companyName\":\"Công ty\",\"introduction\":\"Nội dung\",\"updatedBy\":\"" + adminId + "\"}",
                "{\"companyName\":\"Công ty\",\"introduction\":\"Nội dung\",\"logoMediaId\":\"không-phải-uuid\"}",
                "{\"companyName\":\"Công ty\",\"introduction\":\"Nội dung\",\"imageIds\":[\"abc\"]}",
                "{\"companyName\":\"Công ty\",")) {
            error(request("PUT", EDITOR, malformed, adminToken), 400, "INVALID_JSON");
            error(request("POST", PREVIEW, malformed, adminToken), 400, "INVALID_JSON");
        }

        assertThat(jdbc.queryForMap("SELECT * FROM company_profile")).isEqualTo(rowBefore);
        assertThat(savedImageIds()).containsExactly(image);
    }

    @Test
    void acceptsLengthBoundariesAndPlainTextThatOnlyLooksLikeMarkup() throws Exception {
        List<UUID> tenImages = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            tenImages.add(picture(CompanyMediaKind.IMAGE, 10 + index, 10));
        }
        // '<' not directly followed by a letter, '/', '!' or '?' cannot start HTML, and entities stay text.
        // An invisible joiner (U+200D) inside visible text is kept: it builds the family emoji.
        String lookalikes = "Lương < 20 triệu\n5 <= 10 và <3\n&lt;script&gt; chỉ là chữ\n\tThụt dòng bằng tab\n😀"
                + "\nGia đình 👨‍👩‍👧";
        String introduction = lookalikes + "x".repeat(20_000 - lookalikes.length());
        Map<String, Object> content = payload("n".repeat(255), "t".repeat(255), introduction, null, tenImages);

        JsonNode preview = expect(preview(content, adminToken), 200);
        assertThat(preview.path("introduction").asText()).isEqualTo(introduction);
        assertThat(preview.path("images").size()).isEqualTo(10);
        JsonNode saved = expect(save(content, adminToken), 200);
        assertThat(saved.path("introduction").asText()).isEqualTo(introduction);
        assertThat(jdbc.queryForObject("SELECT introduction FROM company_profile", String.class)).isEqualTo(introduction);
        assertThat(savedImageIds()).containsExactlyElementsOf(tenImages);
        assertThat(expect(get(PUBLIC, null), 200)).isEqualTo(preview);
    }

    @Test
    void rejectsPicturesThatDoNotExistOrWereUploadedForTheOtherUse() throws Exception {
        UUID logo = picture(CompanyMediaKind.LOGO, 100, 100);
        UUID image = picture(CompanyMediaKind.IMAGE, 100, 100);
        List<Map<String, Object>> wrongLogos = List.of(
                payload("Công ty", null, "Nội dung", image, List.of()),
                payload("Công ty", null, "Nội dung", UUID.randomUUID(), List.of()));
        List<Map<String, Object>> wrongImages = List.of(
                payload("Công ty", null, "Nội dung", logo, List.of(logo)),
                payload("Công ty", null, "Nội dung", logo, List.of(image, UUID.randomUUID())));
        for (var body : wrongLogos) {
            for (var response : List.of(save(body, adminToken), preview(body, adminToken))) {
                error(response, 400, "INVALID_COMPANY_LOGO");
                noStore(response);
            }
        }
        for (var body : wrongImages) {
            for (var response : List.of(save(body, adminToken), preview(body, adminToken))) {
                error(response, 400, "INVALID_COMPANY_IMAGE");
                noStore(response);
            }
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM company_profile", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM company_profile_images", Integer.class)).isZero();
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void onlyAdminAndHrManagerUseTheEditorWhileEveryoneReadsThePublicPage(Role role) throws Exception {
        expect(save(payload("Công ty TTCS", null, "Giới thiệu", null, List.of()), adminToken), 200);
        Map<String, Object> rowBefore = jdbc.queryForMap("SELECT * FROM company_profile");
        account("role@example.test", Set.of(role));
        String token = login("role@example.test").path("accessToken").asText();
        Map<String, Object> change = payload("Đổi tên", null, "Nội dung khác", null, List.of());

        var read = get(EDITOR, token);
        var previewed = preview(change, token);
        var saved = save(change, token);
        if (role == Role.ADMIN || role == Role.HR_MANAGER) {
            assertThat(expect(read, 200).path("companyName").asText()).isEqualTo("Công ty TTCS");
            assertThat(expect(previewed, 200).path("companyName").asText()).isEqualTo("Đổi tên");
            assertThat(expect(saved, 200).path("companyName").asText()).isEqualTo("Đổi tên");
        } else {
            // Recruiters hold JOB_POSTINGS_WRITE_SCOPED, which is not enough for the company-wide page.
            for (var response : List.of(read, previewed, saved)) {
                error(response, 403, "FORBIDDEN");
                noStore(response);
            }
            assertThat(jdbc.queryForMap("SELECT * FROM company_profile")).isEqualTo(rowBefore);
        }
        String expectedName = role == Role.ADMIN || role == Role.HR_MANAGER ? "Đổi tên" : "Công ty TTCS";
        assertThat(expect(get(PUBLIC, token), 200).path("companyName").asText()).isEqualTo(expectedName);
        assertThat(expect(get(PUBLIC, null), 200).path("companyName").asText()).isEqualTo(expectedName);
    }

    @ParameterizedTest
    @ValueSource(strings = {"lost-permission", "expired-jwt", "locked-actor", "revoked-session"})
    void rechecksAccessAfterWaitingForTheActorAccountLock(String change) throws Exception {
        // This third account is outside the request, avoiding FK lock interference when it locks the actor.
        UUID lockOwner = account("lockowner@example.test", Set.of(Role.ADMIN));
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blockerPid = lockAccount(connection, adminId);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(() -> save(payload("Công ty", null, "Nội dung", null, List.of()),
                        adminToken));
                try {
                    awaitWaiters(blockerPid, 1);
                    switch (change) {
                        case "lost-permission" -> jdbc.update("DELETE FROM role_permissions "
                                + "WHERE role_code = 'ADMIN' AND permission_code = 'JOB_POSTINGS_WRITE_ALL'");
                        case "expired-jwt" -> clock.set(START.plus(Duration.ofMinutes(15)));
                        case "locked-actor" -> execute(connection, "UPDATE user_accounts SET admin_locked_at = ?, "
                                + "admin_lock_reason = 'Review', admin_locked_by = ? WHERE id = ?",
                                Timestamp.from(START), lockOwner, adminId);
                        default -> execute(connection, "UPDATE auth_sessions SET revoked_at = ? WHERE user_id = ?",
                                Timestamp.from(START), adminId);
                    }
                    connection.commit();
                    var result = response.get(10, TimeUnit.SECONDS);
                    if (change.equals("lost-permission")) {
                        error(result, 403, "FORBIDDEN");
                    } else {
                        error(result, 401, "SESSION_INVALID");
                    }
                } finally {
                    connection.rollback();
                }
            }
        } finally {
            jdbc.update("""
                    INSERT INTO role_permissions (role_code, permission_code)
                    VALUES ('ADMIN', 'JOB_POSTINGS_WRITE_ALL')
                    ON CONFLICT DO NOTHING
                    """);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM company_profile", Integer.class)).isZero();
    }

    @Test
    void twoFirstSavesAtTheSameTimeRunOneAfterTheOtherAndBothSucceed() throws Exception {
        UUID hrManager = account("hr@example.test", Set.of(Role.HR_MANAGER));
        String hrToken = login("hr@example.test").path("accessToken").asText();
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            // Hold the save lock so both requests are certainly waiting before either looks for the row.
            int blockerPid = holdSaveLock(connection);
            try (var executor = Executors.newFixedThreadPool(2)) {
                var byAdmin = executor.submit(() -> save(payload("Bản của Admin", null, "Nội dung A", null,
                        List.of()), adminToken));
                var byHr = executor.submit(() -> save(payload("Bản của HR", null, "Nội dung B", null,
                        List.of()), hrToken));
                try {
                    awaitWaiters(blockerPid, 2);
                    connection.commit();
                    JsonNode adminResult = expect(byAdmin.get(10, TimeUnit.SECONDS), 200);
                    JsonNode hrResult = expect(byHr.get(10, TimeUnit.SECONDS), 200);
                    assertThat(Instant.parse(adminResult.path("createdAt").asText())).isEqualTo(START);
                    assertThat(Instant.parse(hrResult.path("createdAt").asText())).isEqualTo(START);
                } finally {
                    connection.rollback();
                }
            }
        }
        // Exactly one row, holding the content of whichever save ran last.
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM company_profile");
        String expectedName = row.get("updated_by").equals(adminId) ? "Bản của Admin" : "Bản của HR";
        assertThat(row.get("updated_by")).isIn(adminId, hrManager);
        assertThat(row.get("company_name")).isEqualTo(expectedName);
        assertThat(expect(get(PUBLIC, null), 200).path("companyName").asText()).isEqualTo(expectedName);
    }

    private record Invalid(String field, Object value) {
        @Override
        public String toString() {
            String text = String.valueOf(value);
            return field + "=" + (text.length() > 40 ? text.substring(0, 40) + "..." : text);
        }
    }

    private UUID account(String email, Set<Role> roles) {
        return accounts.saveAndFlush(new Account(email, "Company profile test", fixturePasswordHash, roles, START))
                .getId();
    }

    // Only the database checks run here (signature, size, kind); decoding real images belongs to the upload API.
    private UUID picture(CompanyMediaKind kind, int width, int height) {
        byte[] bytes = new byte[64];
        System.arraycopy(PNG_SIGNATURE, 0, bytes, 0, PNG_SIGNATURE.length);
        return media.saveAndFlush(new CompanyMedia(kind, "image/png", bytes, width, height, adminId, START)).getId();
    }

    private Map<String, Object> payload(String companyName, String tagline, String introduction, UUID logoMediaId,
                                        List<UUID> imageIds) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("companyName", companyName);
        result.put("tagline", tagline);
        result.put("introduction", introduction);
        result.put("logoMediaId", logoMediaId);
        result.put("imageIds", imageIds);
        return result;
    }

    private List<UUID> savedImageIds() {
        return jdbc.queryForList("SELECT media_id FROM company_profile_images ORDER BY display_order", UUID.class);
    }

    private void assertPicture(JsonNode picture, UUID id, int width, int height) {
        assertThat(picture.path("id").asText()).isEqualTo(id.toString());
        assertThat(picture.path("width").asInt()).isEqualTo(width);
        assertThat(picture.path("height").asInt()).isEqualTo(height);
        assertThat(picture.path("url").asText()).isEqualTo("/api/v1/public/company-media/" + id);
    }

    private List<String> fieldNames(JsonNode node) {
        return new ArrayList<>(node.propertyNames());
    }

    private List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(item -> values.add(item.asText()));
        return values;
    }

    private JsonNode login(String email) throws Exception {
        return expect(request("POST", "/api/v1/auth/login",
                json.writeValueAsString(Map.of("email", email, "password", PASSWORD)), null), 200);
    }

    private HttpResponse<String> save(Map<String, Object> payload, String token) throws Exception {
        return request("PUT", EDITOR, json.writeValueAsString(payload), token);
    }

    private HttpResponse<String> preview(Map<String, Object> payload, String token) throws Exception {
        return request("POST", PREVIEW, json.writeValueAsString(payload), token);
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return request("GET", path, null, token);
    }

    private HttpResponse<String> request(String method, String path, String payload, String token) throws Exception {
        URI uri = URI.create("http://127.0.0.1:" + environment.getRequiredProperty("local.server.port") + path);
        var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                .method(method, payload == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(payload));
        if (token != null) { builder.header("Authorization", "Bearer " + token); }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode expect(HttpResponse<String> response, int expected) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expected);
        return json.readTree(response.body());
    }

    private void error(HttpResponse<String> response, int status, String code) {
        assertThat(expect(response, status).path("code").asText()).isEqualTo(code);
    }

    private void noStore(HttpResponse<String> response) {
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
    }

    private int lockAccount(Connection connection, UUID id) throws Exception {
        try (var statement = connection.prepareStatement("SELECT id FROM user_accounts WHERE id = ? FOR UPDATE")) {
            statement.setObject(1, id);
            try (var row = statement.executeQuery()) { assertThat(row.next()).isTrue(); }
        }
        return backendPid(connection);
    }

    private int holdSaveLock(Connection connection) throws Exception {
        try (var statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
            statement.setLong(1, CompanyProfileService.WRITE_LOCK_KEY);
            try (var row = statement.executeQuery()) { assertThat(row.next()).isTrue(); }
        }
        return backendPid(connection);
    }

    private int backendPid(Connection connection) throws Exception {
        try (var statement = connection.prepareStatement("SELECT pg_backend_pid()");
             var row = statement.executeQuery()) {
            assertThat(row.next()).isTrue();
            return row.getInt(1);
        }
    }

    private void execute(Connection connection, String sql, Object... values) throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) { statement.setObject(index + 1, values[index]); }
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private void awaitWaiters(int blockerPid, int expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        int observed = 0;
        while (System.nanoTime() < deadline) {
            observed = jdbc.queryForObject("""
                    WITH RECURSIVE blocked(pid) AS (
                        SELECT pid FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))
                        UNION
                        SELECT activity.pid FROM pg_stat_activity activity
                        JOIN blocked ON blocked.pid = ANY(pg_blocking_pids(activity.pid))
                    )
                    SELECT count(*) FROM pg_stat_activity activity JOIN blocked ON blocked.pid = activity.pid
                    WHERE activity.datname = current_database() AND activity.wait_event_type = 'Lock'
                    """, Integer.class, blockerPid);
            if (observed >= expected) { return; }
            Thread.sleep(20);
        }
        assertThat(observed).as("HTTP requests must reach PostgreSQL locks before release").isGreaterThanOrEqualTo(expected);
    }
}
