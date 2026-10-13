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
import vn.ttcs.recruitment.companyprofile.TestImages;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
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
class CompanyMediaIntegrationTest {
    private static final String UPLOAD = "/api/v1/company-profile/media";
    private static final String EDITOR_MEDIA = "/api/v1/company-profile/media/";
    private static final String PUBLIC_MEDIA = "/api/v1/public/company-media/";
    private static final String PASSWORD = "TestingOnly123!";
    private static final Instant START = Instant.parse("2026-10-07T00:00:00Z");

    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private AccountRepository accounts;
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
    void uploadedPicturesReachTheEditorAtOnceAndCandidatesOnlyWhileTheSavedPageUsesThem() throws Exception {
        byte[] logoBytes = TestImages.png(400, 200);
        byte[] officeBytes = TestImages.jpeg(320, 240);
        // The browser's file name and Content-Type are ignored: the type comes from the bytes.
        var logoResponse = upload("LOGO", "logo.PNG", "application/octet-stream", logoBytes, adminToken);
        JsonNode logo = expect(logoResponse, 201);
        noStore(logoResponse);
        JsonNode office = expect(upload("IMAGE", "van-phong.jpg", "image/jpeg", officeBytes, adminToken), 201);

        assertThat(fieldNames(logo)).containsExactlyInAnyOrder("id", "kind", "contentType", "sizeBytes", "width",
                "height", "url");
        UUID logoId = UUID.fromString(logo.path("id").asText());
        UUID officeId = UUID.fromString(office.path("id").asText());
        assertThat(logo.path("kind").asText()).isEqualTo("LOGO");
        assertThat(logo.path("contentType").asText()).isEqualTo("image/png");
        assertThat(logo.path("sizeBytes").asInt()).isEqualTo(logoBytes.length);
        assertThat(logo.path("width").asInt()).isEqualTo(400);
        assertThat(logo.path("height").asInt()).isEqualTo(200);
        assertThat(logo.path("url").asText()).isEqualTo(PUBLIC_MEDIA + logoId);
        assertThat(office.path("kind").asText()).isEqualTo("IMAGE");
        assertThat(office.path("contentType").asText()).isEqualTo("image/jpeg");
        assertThat(office.path("width").asInt()).isEqualTo(320);
        assertThat(office.path("height").asInt()).isEqualTo(240);

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM company_media WHERE id = ?", logoId);
        assertThat(row.get("kind")).isEqualTo("LOGO");
        assertThat(row.get("content_type")).isEqualTo("image/png");
        assertThat(row.get("data")).isEqualTo(logoBytes);
        assertThat(row.get("size_bytes")).isEqualTo(logoBytes.length);
        assertThat(row.get("width")).isEqualTo(400);
        assertThat(row.get("height")).isEqualTo(200);
        assertThat(row.get("created_by")).isEqualTo(adminId);
        assertThat(((Timestamp) row.get("created_at")).toInstant()).isEqualTo(START);

        // Not on the saved page yet: the editor shows it with its token, candidates get 404.
        var unpublished = getBytes(PUBLIC_MEDIA + logoId, null, Map.of());
        assertThat(unpublished.statusCode()).isEqualTo(404);
        assertThat(json.readTree(unpublished.body()).path("code").asText()).isEqualTo("COMPANY_MEDIA_NOT_FOUND");
        assertThat(unpublished.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        var editorCopy = getBytes(EDITOR_MEDIA + logoId, adminToken, Map.of());
        assertPicture(editorCopy, "image/png", logoBytes);
        assertThat(editorCopy.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(editorCopy.headers().firstValue("ETag")).isEmpty();
        assertPicture(getBytes(EDITOR_MEDIA + officeId, adminToken, Map.of()), "image/jpeg", officeBytes);

        // A logo cannot be used as an introduction image, nor the other way round.
        error(savePage(officeId, List.of(logoId)), 400, "INVALID_COMPANY_LOGO");
        error(savePage(logoId, List.of(logoId)), 400, "INVALID_COMPANY_IMAGE");
        expect(savePage(logoId, List.of(officeId)), 200);

        // The public page gives the address of each picture, and that address works without a token.
        JsonNode page = expect(request("GET", "/api/v1/public/company-profile", null, null), 200);
        assertThat(fieldNames(page.path("logo"))).containsExactlyInAnyOrder("id", "width", "height", "url");
        assertThat(page.path("logo").path("url").asText()).isEqualTo(PUBLIC_MEDIA + logoId);
        assertThat(page.path("images").get(0).path("url").asText()).isEqualTo(office.path("url").asText());
        var published = getBytes(page.path("logo").path("url").asText(), null, Map.of());
        assertPicture(published, "image/png", logoBytes);
        assertThat(published.headers().firstValue("Cache-Control").orElseThrow()).isEqualTo("max-age=3600, public");
        assertThat(published.headers().firstValue("Pragma")).isEmpty();
        String etag = published.headers().firstValue("ETag").orElseThrow();
        assertThat(etag).isEqualTo("\"" + logoId + "\"");
        assertPicture(getBytes(PUBLIC_MEDIA + officeId, null, Map.of()), "image/jpeg", officeBytes);

        // A browser that still has the picture asks with its ETag and gets an empty 304.
        var notModified = getBytes(PUBLIC_MEDIA + logoId, null, Map.of("If-None-Match", etag));
        assertThat(notModified.statusCode()).isEqualTo(304);
        assertThat(notModified.body()).isEmpty();

        // Removed from the page: candidates get 404 again, the editor still has the picture.
        expect(savePage(logoId, List.of()), 200);
        assertThat(getBytes(PUBLIC_MEDIA + officeId, null, Map.of()).statusCode()).isEqualTo(404);
        assertThat(getBytes(PUBLIC_MEDIA + officeId, null, Map.of("If-None-Match", "\"" + officeId + "\""))
                .statusCode()).isEqualTo(404);
        assertPicture(getBytes(EDITOR_MEDIA + officeId, adminToken, Map.of()), "image/jpeg", officeBytes);
        assertPicture(getBytes(PUBLIC_MEDIA + logoId, null, Map.of()), "image/png", logoBytes);
        assertThat(mediaCount()).isEqualTo(2);
    }

    @Test
    void refusesFilesThatAreNotCompleteJpegOrPngPicturesAndStoresNothing() throws Exception {
        byte[] pngSignature = Arrays.copyOf(TestImages.png(8, 8), 8);
        List<Refused> cases = List.of(
                new Refused("anh.gif", "image/gif", TestImages.gif(10, 10), "UNSUPPORTED_COMPANY_MEDIA_TYPE"),
                new Refused("anh.png", "image/png", TestImages.gif(10, 10), "UNSUPPORTED_COMPANY_MEDIA_TYPE"),
                new Refused("anh.bmp", "image/bmp", TestImages.bmp(10, 10), "UNSUPPORTED_COMPANY_MEDIA_TYPE"),
                new Refused("logo.svg", "image/svg+xml", text("<svg xmlns=\"http://www.w3.org/2000/svg\">"
                        + "<script>alert(1)</script></svg>"), "UNSUPPORTED_COMPANY_MEDIA_TYPE"),
                new Refused("logo.png", "image/png", text("<html><script>alert(1)</script></html>"),
                        "UNSUPPORTED_COMPANY_MEDIA_TYPE"),
                new Refused("logo.png", "image/png", concat(pngSignature, text("<script>alert(1)</script>")),
                        "INVALID_COMPANY_MEDIA"),
                new Refused("cat-doi.jpg", "image/jpeg", TestImages.jpegCutInPictureData(64, 48),
                        "INVALID_COMPANY_MEDIA"),
                new Refused("bom.png", "image/png", TestImages.pngClaimingSize(100_000, 100_000),
                        "COMPANY_MEDIA_DIMENSIONS_TOO_LARGE"),
                new Refused("rong.png", "image/png", TestImages.png(6001, 10), "COMPANY_MEDIA_DIMENSIONS_TOO_LARGE"),
                new Refused("trong.png", "image/png", new byte[0], "VALIDATION_ERROR"));
        for (Refused refused : cases) {
            for (String kind : List.of("LOGO", "IMAGE")) {
                var response = upload(kind, refused.fileName(), refused.contentType(), refused.data(), adminToken);
                assertThat(json.readTree(response.body()).path("code").asText()).as(refused + " " + kind)
                        .isEqualTo(refused.code());
                assertThat(response.statusCode()).as(refused + " " + kind).isEqualTo(400);
                noStore(response);
            }
        }

        byte[] png = TestImages.png(10, 10);
        error(upload(null, "logo.png", "image/png", png, adminToken), 400, "VALIDATION_ERROR");
        error(upload("", "logo.png", "image/png", png, adminToken), 400, "VALIDATION_ERROR");
        error(upload("BANNER", "logo.png", "image/png", png, adminToken), 400, "VALIDATION_ERROR");
        error(upload("logo", "logo.png", "image/png", png, adminToken), 400, "VALIDATION_ERROR");
        error(upload("LOGO", null, null, null, adminToken), 400, "VALIDATION_ERROR");
        // The upload is multipart/form-data; a JSON body is refused before the controller runs.
        assertThat(request("POST", UPLOAD, "{\"kind\":\"LOGO\"}", adminToken).statusCode()).isEqualTo(415);
        // A body that only claims to be multipart has no fields at all.
        var notMultipart = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                        + environment.getRequiredProperty("local.server.port") + UPLOAD))
                .header("Content-Type", "multipart/form-data; boundary=missing")
                .header("Authorization", "Bearer " + adminToken)
                .POST(HttpRequest.BodyPublishers.ofString("not a multipart body")).build();
        error(client.send(notMultipart, HttpResponse.BodyHandlers.ofString()), 400, "VALIDATION_ERROR");
        assertThat(mediaCount()).isZero();
    }

    @Test
    void filesAboveFiveMegabytesGet413ButExactlyFiveMegabytesIsStored() throws Exception {
        byte[] tooLarge = TestImages.pngOfExactSize(40, 30, CompanyMedia.MAX_SIZE_BYTES + 1);
        var refused = upload("IMAGE", "lon.png", "image/png", tooLarge, adminToken);
        // Refused by the multipart limit of application.properties while the upload is read, not by the service.
        assertThat(expect(refused, 413).path("message").asText()).isEqualTo("Tệp tải lên vượt quá dung lượng cho phép.");
        error(refused, 413, "FILE_TOO_LARGE");
        noStore(refused);
        assertThat(mediaCount()).isZero();

        byte[] largest = TestImages.pngOfExactSize(40, 30, CompanyMedia.MAX_SIZE_BYTES);
        JsonNode stored = expect(upload("IMAGE", "vua-du.png", "image/png", largest, adminToken), 201);
        assertThat(stored.path("sizeBytes").asInt()).isEqualTo(5 * 1024 * 1024);
        assertThat(jdbc.queryForObject("SELECT octet_length(data) FROM company_media", Integer.class))
                .isEqualTo(5 * 1024 * 1024);
    }

    // Phone photos are often 7-12 MB. A request above the 6 MB request limit is refused from its Content-Length
    // before the body is read; server.tomcat.max-swallow-size=20MB lets Tomcat read and drop the rest of the body,
    // so the client still receives the whole 413 JSON instead of a cut connection.
    @ParameterizedTest
    @ValueSource(ints = {6, 12, 19})
    void filesAboveTheRequestLimitStillReceiveTheWhole413(int megabytes) throws Exception {
        byte[] photo = TestImages.pngOfExactSize(40, 30, megabytes * 1024 * 1024);
        var refused = upload("IMAGE", "anh-dien-thoai.png", "image/png", photo, adminToken);
        assertThat(expect(refused, 413).path("message").asText()).isEqualTo("Tệp tải lên vượt quá dung lượng cho phép.");
        error(refused, 413, "FILE_TOO_LARGE");
        noStore(refused);
        assertThat(mediaCount()).isZero();
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void onlyAdminAndHrManagerUploadOrOpenUnsavedPicturesWhileEveryoneSeesPublishedOnes(Role role) throws Exception {
        UUID published = UUID.fromString(expect(upload("LOGO", "logo.png", "image/png", TestImages.png(20, 20),
                adminToken), 201).path("id").asText());
        UUID unpublished = UUID.fromString(expect(upload("IMAGE", "anh.png", "image/png", TestImages.png(30, 20),
                adminToken), 201).path("id").asText());
        expect(savePage(published, List.of()), 200);
        UUID member = account("role@example.test", Set.of(role));
        String token = login("role@example.test").path("accessToken").asText();

        var uploaded = upload("IMAGE", "anh.jpg", "image/jpeg", TestImages.jpeg(30, 20), token);
        var opened = getBytes(EDITOR_MEDIA + unpublished, token, Map.of());
        if (role == Role.ADMIN || role == Role.HR_MANAGER) {
            JsonNode result = expect(uploaded, 201);
            UUID id = UUID.fromString(result.path("id").asText());
            assertThat(jdbc.queryForObject("SELECT created_by FROM company_media WHERE id = ?", UUID.class, id))
                    .isEqualTo(member);
            assertThat(opened.statusCode()).isEqualTo(200);
            assertThat(mediaCount()).isEqualTo(3);
        } else {
            // Recruiters hold JOB_POSTINGS_WRITE_SCOPED, which is not enough for the company-wide page.
            error(uploaded, 403, "FORBIDDEN");
            noStore(uploaded);
            assertThat(opened.statusCode()).isEqualTo(403);
            assertThat(json.readTree(opened.body()).path("code").asText()).isEqualTo("FORBIDDEN");
            assertThat(mediaCount()).isEqualTo(2);
        }
        assertThat(getBytes(PUBLIC_MEDIA + published, token, Map.of()).statusCode()).isEqualTo(200);
        assertThat(getBytes(PUBLIC_MEDIA + published, null, Map.of()).statusCode()).isEqualTo(200);
        assertThat(getBytes(PUBLIC_MEDIA + unpublished, token, Map.of()).statusCode()).isEqualTo(404);
    }

    @Test
    void editorEndpointsNeedLoginAndUnknownOrMalformedIdsAreReported() throws Exception {
        byte[] png = TestImages.png(10, 10);
        var anonymousUpload = upload("LOGO", "logo.png", "image/png", png, null);
        assertThat(anonymousUpload.statusCode()).isEqualTo(401);
        assertThat(json.readTree(anonymousUpload.body()).path("code").asText()).isEqualTo("UNAUTHORIZED");
        UUID stored = UUID.fromString(expect(upload("LOGO", "logo.png", "image/png", png, adminToken), 201)
                .path("id").asText());
        assertThat(getBytes(EDITOR_MEDIA + stored, null, Map.of()).statusCode()).isEqualTo(401);

        UUID unknown = UUID.randomUUID();
        error(request("GET", EDITOR_MEDIA + unknown, null, adminToken), 404, "COMPANY_MEDIA_NOT_FOUND");
        error(request("GET", PUBLIC_MEDIA + unknown, null, null), 404, "COMPANY_MEDIA_NOT_FOUND");
        error(request("GET", EDITOR_MEDIA + "khong-phai-uuid", null, adminToken), 400, "VALIDATION_ERROR");
        error(request("GET", PUBLIC_MEDIA + "khong-phai-uuid", null, null), 400, "VALIDATION_ERROR");
        assertThat(mediaCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"lost-permission", "revoked-session"})
    void uploadRechecksAccessAfterWaitingForTheActorAccountLock(String change) throws Exception {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blockerPid = lockAccount(connection, adminId);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(() -> upload("LOGO", "logo.png", "image/png", TestImages.png(10, 10),
                        adminToken));
                try {
                    awaitWaiters(blockerPid);
                    if (change.equals("lost-permission")) {
                        jdbc.update("DELETE FROM role_permissions "
                                + "WHERE role_code = 'ADMIN' AND permission_code = 'JOB_POSTINGS_WRITE_ALL'");
                    } else {
                        execute(connection, "UPDATE auth_sessions SET revoked_at = ? WHERE user_id = ?",
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
        assertThat(mediaCount()).isZero();
    }

    private record Refused(String fileName, String contentType, byte[] data, String code) {
        @Override
        public String toString() {
            return fileName + " (" + contentType + ", " + data.length + " bytes)";
        }
    }

    private UUID account(String email, Set<Role> roles) {
        return accounts.saveAndFlush(new Account(email, "Company media test", fixturePasswordHash, roles, START))
                .getId();
    }

    private HttpResponse<String> savePage(UUID logoMediaId, List<UUID> imageIds) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("companyName", "Công ty TTCS");
        payload.put("introduction", "Giới thiệu");
        payload.put("logoMediaId", logoMediaId);
        payload.put("imageIds", imageIds);
        return request("PUT", "/api/v1/company-profile", json.writeValueAsString(payload), adminToken);
    }

    private int mediaCount() {
        return jdbc.queryForObject("SELECT count(*) FROM company_media", Integer.class);
    }

    private void assertPicture(HttpResponse<byte[]> response, String contentType, byte[] expected) {
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(expected);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).isEqualTo(contentType);
        assertThat(response.headers().firstValueAsLong("Content-Length").orElseThrow()).isEqualTo(expected.length);
        assertThat(response.headers().allValues("X-Content-Type-Options")).containsExactly("nosniff");
        assertThat(response.headers().firstValue("Content-Security-Policy").orElseThrow())
                .isEqualTo("default-src 'none'; sandbox");
    }

    private List<String> fieldNames(JsonNode node) {
        return new ArrayList<>(node.propertyNames());
    }

    private JsonNode login(String email) throws Exception {
        return expect(request("POST", "/api/v1/auth/login",
                json.writeValueAsString(Map.of("email", email, "password", PASSWORD)), null), 200);
    }

    // Builds a multipart/form-data body like a browser form: a "kind" text field and a "file" field.
    // A null kind or a null file leaves that field out.
    private HttpResponse<String> upload(String kind, String fileName, String contentType, byte[] data, String token)
            throws Exception {
        String boundary = "ttcs-" + UUID.randomUUID();
        var body = new ByteArrayOutputStream();
        if (kind != null) {
            body.write(text("--" + boundary + "\r\nContent-Disposition: form-data; name=\"kind\"\r\n\r\n"
                    + kind + "\r\n"));
        }
        if (data != null) {
            body.write(text("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                    + fileName + "\"\r\nContent-Type: " + contentType + "\r\n\r\n"));
            body.write(data);
            body.write(text("\r\n"));
        }
        body.write(text("--" + boundary + "--\r\n"));
        URI uri = URI.create("http://127.0.0.1:" + environment.getRequiredProperty("local.server.port") + UPLOAD);
        var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
        if (token != null) { builder.header("Authorization", "Bearer " + token); }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<byte[]> getBytes(String path, String token, Map<String, String> headers) throws Exception {
        URI uri = URI.create("http://127.0.0.1:" + environment.getRequiredProperty("local.server.port") + path);
        var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).GET();
        headers.forEach(builder::header);
        if (token != null) { builder.header("Authorization", "Bearer " + token); }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
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

    private static byte[] text(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private int lockAccount(Connection connection, UUID id) throws Exception {
        try (var statement = connection.prepareStatement("SELECT id FROM user_accounts WHERE id = ? FOR UPDATE")) {
            statement.setObject(1, id);
            try (var row = statement.executeQuery()) { assertThat(row.next()).isTrue(); }
        }
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

    private void awaitWaiters(int blockerPid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        int observed = 0;
        while (System.nanoTime() < deadline) {
            observed = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE ? = ANY(pg_blocking_pids(pid)) AND datname = current_database() AND wait_event_type = 'Lock'
                    """, Integer.class, blockerPid);
            if (observed >= 1) { return; }
            Thread.sleep(20);
        }
        assertThat(observed).as("the upload must reach the account lock before it is released").isEqualTo(1);
    }
}
