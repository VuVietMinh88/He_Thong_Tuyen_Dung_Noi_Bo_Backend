package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.account.avatar.AvatarService;
import vn.ttcs.recruitment.account.avatar.AvatarSize;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import javax.sql.DataSource;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

import static java.awt.image.BufferedImage.TYPE_INT_RGB;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=false",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProfileAvatarIntegrationTest {
    private static final String OWN = "/api/v1/profile/avatar";
    private static final String PASSWORD = "TestingOnly123!";
    private static final String EMAIL = "lap@example.test";
    private static final String COLLEAGUE_EMAIL = "colleague@example.test";
    private static final Instant START = Instant.parse("2026-10-07T00:00:00Z");
    private static final int TWO_MEGABYTES = 2 * 1024 * 1024;
    private static final int FIVE_MEGABYTES = 5 * 1024 * 1024;
    private static final Color BLUE = new Color(0x33, 0x66, 0xCC);
    private static final Color GREEN = new Color(0x22, 0xAA, 0x44);
    private static final Color RED = new Color(0xDD, 0x22, 0x22);

    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private AccountRepository accounts;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private AvatarService avatars;
    @Autowired private AuthIntegrationTest.MutableClock clock;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private String passwordHash;
    private UUID userId;
    private UUID sessionId;
    private String token;
    private UUID colleagueId;
    private String colleagueToken;

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
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("UPDATE user_accounts SET department_id = NULL, admin_locked_at = NULL, admin_lock_reason = NULL, admin_locked_by = NULL");
        jdbc.update("DELETE FROM departments");
        jdbc.update("DELETE FROM user_accounts"); // ON DELETE CASCADE also removes every avatar
        jdbc.update("""
                INSERT INTO role_permissions (role_code, permission_code)
                SELECT code, 'SELF_PROFILE_READ' FROM roles WHERE internal
                UNION ALL SELECT code, 'SELF_PROFILE_WRITE' FROM roles WHERE internal
                ON CONFLICT DO NOTHING
                """);
        passwordHash = passwordEncoder.encode(PASSWORD);
        userId = account(EMAIL, Set.of(Role.RECRUITER));
        colleagueId = account(COLLEAGUE_EMAIL, Set.of(Role.INTERVIEWER));
        token = login(EMAIL);
        colleagueToken = login(COLLEAGUE_EMAIL);
        sessionId = jdbc.queryForObject("SELECT id FROM auth_sessions WHERE user_id = ?", UUID.class, userId);
    }

    @Test
    void uploadKeepsTheMiddleSquareAndSavesBothSizesOnTheCallersProfile() throws Exception {
        // 300 x 100 pixels: a blue square in the middle between two red strips. Only the blue square is kept.
        BufferedImage wide = filled(300, 100, RED);
        Graphics2D graphics = wide.createGraphics();
        graphics.setColor(BLUE);
        graphics.fillRect(100, 0, 100, 100);
        graphics.dispose();

        var response = upload(token, "anh-dai-dien.png", encode(wide, "png"));
        JsonNode view = expect(response, 200);
        noStore(response);
        assertThat(view.size()).isEqualTo(6);
        assertThat(view.path("userId").asText()).isEqualTo(userId.toString());
        assertThat(view.path("contentType").asText()).isEqualTo("image/png");
        assertThat(Instant.parse(view.path("updatedAt").asText())).isEqualTo(START);
        assertThat(view.path("imageUrl").asText()).isEqualTo("/api/v1/accounts/" + userId + "/avatar");
        assertThat(view.path("thumbnailUrl").asText()).isEqualTo("/api/v1/accounts/" + userId + "/avatar?size=thumbnail");

        var full = get(OWN, token);
        assertThat(full.statusCode()).isEqualTo(200);
        assertThat(full.headers().firstValue("Content-Type").orElseThrow()).isEqualTo("image/png");
        assertThat(full.headers().firstValue("X-Content-Type-Options").orElseThrow()).isEqualTo("nosniff");
        noStore(full);
        assertSquareOf(decode(full.body()), 256, BLUE);
        assertThat(view.path("sizeBytes").asInt()).isEqualTo(full.body().length);
        assertThat(get(OWN + "?size=full", token).body()).isEqualTo(full.body());

        var thumbnail = get(OWN + "?size=thumbnail", token);
        assertThat(thumbnail.statusCode()).isEqualTo(200);
        assertThat(thumbnail.headers().firstValue("Content-Type").orElseThrow()).isEqualTo("image/png");
        noStore(thumbnail);
        assertSquareOf(decode(thumbnail.body()), 64, BLUE);

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM user_avatars WHERE user_id = ?", userId);
        assertThat(row.get("content_type")).isEqualTo("image/png");
        assertThat((byte[]) row.get("image")).isEqualTo(full.body());
        assertThat((byte[]) row.get("thumbnail")).isEqualTo(thumbnail.body());
        assertThat(row.get("size_bytes")).isEqualTo(full.body().length);
        assertThat(((Timestamp) row.get("updated_at")).toInstant()).isEqualTo(START);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_avatars", Integer.class)).isEqualTo(1);

        JsonNode profile = expect(get("/api/v1/profile", token), 200);
        assertThat(profile.path("id").asText()).isEqualTo(userId.toString());
        assertThat(profile.path("hasAvatar").asBoolean()).isTrue();
        assertThat(Instant.parse(profile.path("avatarUpdatedAt").asText())).isEqualTo(START);
    }

    @Test
    void profileOfAnAccountWithoutAvatarSaysSoAndKeepsTheEarlierFields() throws Exception {
        JsonNode profile = expect(get("/api/v1/profile", token), 200);
        assertThat(profile.path("hasAvatar").isBoolean()).isTrue();
        assertThat(profile.path("hasAvatar").asBoolean()).isFalse();
        assertThat(profile.has("avatarUpdatedAt")).isTrue();
        assertThat(profile.path("avatarUpdatedAt").isNull()).isTrue();
        assertThat(profile.path("email").asText()).isEqualTo(EMAIL);
        assertThat(profile.path("roles").toString()).isEqualTo("[\"RECRUITER\"]");
        error(get(OWN, token), 404, "AVATAR_NOT_FOUND");
    }

    @Test
    void anotherUploadReplacesTheOnlyRowAndJpegIsStoredAsPngToo() throws Exception {
        expect(upload(token, "xanh.png", png(80, 80, BLUE)), 200);
        byte[] firstImage = get(OWN, token).body();
        Instant later = START.plus(Duration.ofMinutes(5)); // still inside the access token's lifetime
        clock.set(later);

        JsonNode view = expect(upload(token, "xanh-la.jpg", encode(filled(500, 400, GREEN), "jpg")), 200);
        assertThat(view.path("contentType").asText()).isEqualTo("image/png");
        assertThat(Instant.parse(view.path("updatedAt").asText())).isEqualTo(later);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_avatars WHERE user_id = ?", Integer.class, userId))
                .isEqualTo(1);
        byte[] secondImage = get(OWN, token).body();
        assertThat(secondImage).isNotEqualTo(firstImage);
        BufferedImage avatar = decode(secondImage);
        assertThat(avatar.getWidth()).isEqualTo(256);
        assertCloseTo(GREEN, avatar.getRGB(128, 128));
        assertCloseTo(GREEN, decode(get(OWN + "?size=thumbnail", token).body()).getRGB(32, 32));
        assertThat(Instant.parse(expect(get("/api/v1/profile", token), 200).path("avatarUpdatedAt").asText()))
                .isEqualTo(later);
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void everyInternalRoleSeesAColleaguesAvatarInBothSizes(Role role) throws Exception {
        expect(upload(token, "a.png", png(64, 64, BLUE)), 200);
        jdbc.update("UPDATE user_roles SET role = ? WHERE user_id = ?", role.name(), colleagueId);

        var full = get("/api/v1/accounts/" + userId + "/avatar", colleagueToken);
        assertThat(full.statusCode()).isEqualTo(200);
        assertThat(full.headers().firstValue("Content-Type").orElseThrow()).isEqualTo("image/png");
        noStore(full);
        assertThat(full.body()).isEqualTo(get(OWN, token).body());
        var thumbnail = get("/api/v1/accounts/" + userId + "/avatar?size=thumbnail", colleagueToken);
        assertThat(thumbnail.statusCode()).isEqualTo(200);
        assertThat(thumbnail.body()).isEqualTo(get(OWN + "?size=thumbnail", token).body());
        // Looking at a colleague's picture never gives the viewer an avatar of their own.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_avatars WHERE user_id = ?", Integer.class, colleagueId))
                .isZero();
    }

    @Test
    void accountWithoutRolesIsForbiddenAndMissingAvatarsOrBadParametersGetClearErrors() throws Exception {
        expect(upload(token, "a.png", png(64, 64, BLUE)), 200);
        UUID noRoleId = account("no-role@example.test", Set.of());
        String noRoleToken = login("no-role@example.test");
        assertForbidden(get("/api/v1/accounts/" + userId + "/avatar", noRoleToken));
        assertForbidden(get(OWN, noRoleToken));
        assertForbidden(upload(noRoleToken, "a.png", png(64, 64, GREEN)));
        assertForbidden(delete(noRoleToken));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_avatars WHERE user_id = ?", Integer.class, noRoleId))
                .isZero();

        error(get(OWN, colleagueToken), 404, "AVATAR_NOT_FOUND");
        error(get("/api/v1/accounts/" + colleagueId + "/avatar", token), 404, "AVATAR_NOT_FOUND");
        error(get("/api/v1/accounts/" + UUID.randomUUID() + "/avatar?size=thumbnail", token), 404, "AVATAR_NOT_FOUND");
        error(get("/api/v1/accounts/not-a-uuid/avatar", token), 400, "VALIDATION_ERROR");
        for (String size : List.of("small", "FULL", "Thumbnail", "256")) {
            error(get(OWN + "?size=" + size, token), 400, "VALIDATION_ERROR");
            error(get("/api/v1/accounts/" + userId + "/avatar?size=" + size, colleagueToken), 400, "VALIDATION_ERROR");
        }
    }

    @Test
    void rejectedUploadsExplainTheProblemAndKeepThePreviousAvatar() throws Exception {
        expect(upload(token, "cu.png", png(80, 80, BLUE)), 200);
        Map<String, Object> before = avatarState(userId);
        clock.set(START.plusSeconds(60));
        byte[] valid = png(64, 64, GREEN);
        String boundary = "ttcs-cut-off";
        byte[] cutOffBody = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.png\"\r\n"
                + "Content-Type: image/png\r\n\r\nnot finished").getBytes(StandardCharsets.UTF_8);

        error(upload(token, "dong.gif", encode(filled(64, 64, GREEN), "gif")), 415, "AVATAR_TYPE_UNSUPPORTED");
        error(upload(token, "ghi-chu.png", "không phải ảnh".getBytes(StandardCharsets.UTF_8)), 415,
                "AVATAR_TYPE_UNSUPPORTED");
        error(upload(token, "hong.png", Arrays.copyOf(valid, valid.length / 2)), 400, "AVATAR_INVALID");
        error(upload(token, "rong.png", new byte[0]), 400, "AVATAR_INVALID");
        error(upload(token, "qua-rong.png", png(4097, 1, GREEN)), 400, "AVATAR_DIMENSIONS_TOO_LARGE");
        error(upload(token, "qua-nang.png", pngOfExactly(TWO_MEGABYTES + 1)), 413, "AVATAR_TOO_LARGE");
        error(upload(token, "rat-nang.png", new byte[FIVE_MEGABYTES + 1]), 413, "FILE_TOO_LARGE");
        error(send("PUT", OWN, token, multipart("avatar", "sai-truong.png", valid)), 400, "AVATAR_FILE_REQUIRED");
        error(send("PUT", OWN, token, "application/json", "{}".getBytes(StandardCharsets.UTF_8)), 400,
                "AVATAR_FILE_REQUIRED");
        error(send("PUT", OWN, token, "multipart/form-data; boundary=" + boundary, cutOffBody), 400,
                "INVALID_MULTIPART");

        assertThat(avatarState(userId)).isEqualTo(before);
    }

    @Test
    void aFileOfExactlyTwoMegabytesIsAccepted() throws Exception {
        // Spring's default multipart limit is 1MB; this proves the server limit leaves room for the 2MB rule.
        byte[] largest = pngOfExactly(TWO_MEGABYTES);
        assertThat(largest).hasSize(TWO_MEGABYTES);
        expect(upload(token, "dung-2mb.png", largest), 200);
        assertThat(decode(get(OWN, token).body()).getWidth()).isEqualTo(256);
    }

    @Test
    void storedPicturesDoNotKeepTextHiddenInTheUploadedFile() throws Exception {
        String secret = "GPS 21.0285,105.8542";
        byte[] withComment = withPngChunk(png(64, 64, BLUE), "tEXt",
                ("Comment\0" + secret).getBytes(StandardCharsets.ISO_8859_1));
        assertThat(latin1(withComment)).contains(secret);

        expect(upload(token, "co-ghi-chu.png", withComment), 200);

        assertThat(latin1(stored(userId, "image"))).doesNotContain(secret);
        assertThat(latin1(stored(userId, "thumbnail"))).doesNotContain(secret);
    }

    @Test
    void deleteRemovesOnlyTheCallersAvatarAndCanBeRepeated() throws Exception {
        expect(upload(token, "a.png", png(64, 64, BLUE)), 200);
        expect(upload(colleagueToken, "b.png", png(64, 64, GREEN)), 200);
        Map<String, Object> colleagueBefore = avatarState(colleagueId);

        var deleted = delete(token);
        assertThat(deleted.statusCode()).isEqualTo(204);
        assertThat(deleted.body()).isEmpty();
        noStore(deleted);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_avatars WHERE user_id = ?", Integer.class, userId))
                .isZero();
        error(get(OWN, token), 404, "AVATAR_NOT_FOUND");
        error(get("/api/v1/accounts/" + userId + "/avatar", colleagueToken), 404, "AVATAR_NOT_FOUND");
        JsonNode profile = expect(get("/api/v1/profile", token), 200);
        assertThat(profile.path("hasAvatar").asBoolean()).isFalse();
        assertThat(profile.path("avatarUpdatedAt").isNull()).isTrue();

        assertThat(delete(token).statusCode()).isEqualTo(204);
        assertThat(avatarState(colleagueId)).isEqualTo(colleagueBefore);
    }

    @Test
    void removedPermissionsStopHttpAndServiceCallsThatUseTheSameToken() throws Exception {
        expect(upload(token, "a.png", png(64, 64, BLUE)), 200);
        Map<String, Object> before = avatarState(userId);
        Jwt jwt = jwt(userId, sessionId);

        jdbc.update("DELETE FROM role_permissions WHERE role_code = 'RECRUITER' AND permission_code = 'SELF_PROFILE_WRITE'");
        assertForbidden(upload(token, "b.png", png(64, 64, GREEN)));
        assertForbidden(delete(token));
        assertThatThrownBy(() -> avatars.replace(jwt, file(png(64, 64, GREEN)))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> avatars.delete(jwt)).isInstanceOf(AccessDeniedException.class);
        assertThat(get(OWN, token).statusCode()).isEqualTo(200);

        jdbc.update("DELETE FROM role_permissions WHERE role_code = 'RECRUITER' AND permission_code = 'SELF_PROFILE_READ'");
        assertForbidden(get(OWN, token));
        assertForbidden(get("/api/v1/accounts/" + userId + "/avatar", token));
        assertThatThrownBy(() -> avatars.getOwn(jwt, AvatarSize.FULL)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> avatars.getForAccount(jwt, userId, AvatarSize.THUMBNAIL))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(avatarState(userId)).isEqualTo(before);
    }

    @Test
    void anonymousRevokedLockedDisabledAndExpiredCallersGet401AndChangeNothing() throws Exception {
        expect(upload(token, "a.png", png(64, 64, BLUE)), 200);
        Map<String, Object> before = avatarState(userId);

        assertEveryAvatarApiAnswers401(null);
        jdbc.update("UPDATE auth_sessions SET revoked_at = ? WHERE id = ?", Timestamp.from(START), sessionId);
        assertEveryAvatarApiAnswers401(token);
        jdbc.update("UPDATE auth_sessions SET revoked_at = NULL WHERE id = ?", sessionId);
        jdbc.update("UPDATE user_accounts SET admin_locked_at = ?, admin_lock_reason = 'Review', admin_locked_by = ? WHERE id = ?",
                Timestamp.from(START), colleagueId, userId);
        assertEveryAvatarApiAnswers401(token);
        jdbc.update("UPDATE user_accounts SET admin_locked_at = NULL, admin_lock_reason = NULL, admin_locked_by = NULL WHERE id = ?",
                userId);
        jdbc.update("UPDATE user_accounts SET enabled = false WHERE id = ?", userId);
        assertEveryAvatarApiAnswers401(token);
        jdbc.update("UPDATE user_accounts SET enabled = true WHERE id = ?", userId);
        clock.set(START.plus(TokenService.ACCESS_TOKEN_TTL));
        assertEveryAvatarApiAnswers401(token);

        assertThat(avatarState(userId)).isEqualTo(before);
    }

    @Test
    void serviceRechecksSessionOwnerRevocationAndExpiryBeforeSaving() {
        var picture = file(png(64, 64, GREEN));
        // The colleague's id with the user's session: the session belongs to someone else.
        assertThatThrownBy(() -> avatars.replace(jwt(colleagueId, sessionId), picture))
                .isInstanceOf(AuthenticationFailureException.class);
        assertThatThrownBy(() -> avatars.delete(jwt(colleagueId, sessionId)))
                .isInstanceOf(AuthenticationFailureException.class);
        jdbc.update("UPDATE auth_sessions SET revoked_at = ? WHERE id = ?", Timestamp.from(START), sessionId);
        assertThatThrownBy(() -> avatars.replace(jwt(userId, sessionId), picture))
                .isInstanceOf(AuthenticationFailureException.class);
        jdbc.update("UPDATE auth_sessions SET revoked_at = NULL WHERE id = ?", sessionId);
        clock.set(START.plus(TokenService.ACCESS_TOKEN_TTL));
        assertThatThrownBy(() -> avatars.replace(jwt(userId, sessionId), picture))
                .isInstanceOf(AuthenticationFailureException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_avatars", Integer.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"locked-account", "revoked-session", "lost-permission"})
    void rechecksTheCallerAfterWaitingForTheAccountLock(String change) throws Exception {
        byte[] picture = png(64, 64, GREEN);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blockerPid = lockAccount(connection, userId);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(() -> upload(token, "cho.png", picture));
                try {
                    awaitWaiters(blockerPid, 1);
                    switch (change) {
                        case "locked-account" -> execute(connection,
                                "UPDATE user_accounts SET admin_locked_at = ?, admin_lock_reason = 'Review', admin_locked_by = ? WHERE id = ?",
                                Timestamp.from(START), colleagueId, userId);
                        case "revoked-session" -> execute(connection,
                                "UPDATE auth_sessions SET revoked_at = ? WHERE id = ?", Timestamp.from(START), sessionId);
                        default -> jdbc.update(
                                "DELETE FROM role_permissions WHERE role_code = 'RECRUITER' AND permission_code = 'SELF_PROFILE_WRITE'");
                    }
                    connection.commit();
                    HttpResponse<byte[]> result = response.get(10, TimeUnit.SECONDS);
                    if (change.equals("lost-permission")) {
                        assertForbidden(result);
                    } else {
                        // This 401 comes from ApiExceptionHandler, which (unlike the Bearer filter) sets no no-store.
                        assertThat(expect(result, 401).path("code").asText()).isEqualTo("SESSION_INVALID");
                    }
                } finally {
                    connection.rollback();
                }
            }
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_avatars", Integer.class)).isZero();
    }

    @Test
    void twoUploadsWaitingAtTheSameTimeLeaveOneCompleteAvatar() throws Exception {
        byte[] blue = png(64, 64, BLUE);
        byte[] green = png(64, 64, GREEN);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blockerPid = lockAccount(connection, userId);
            try (var executor = Executors.newFixedThreadPool(2)) {
                var first = executor.submit(() -> upload(token, "xanh.png", blue));
                var second = executor.submit(() -> upload(token, "xanh-la.png", green));
                try {
                    // Both requests are past validation and wait for the same account row before writing.
                    awaitWaiters(blockerPid, 2);
                    connection.commit();
                    assertThat(first.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
                    assertThat(second.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
                } finally {
                    connection.rollback();
                }
            }
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_avatars", Integer.class)).isEqualTo(1);
        int imageColour = decode(stored(userId, "image")).getRGB(128, 128);
        assertThat(imageColour).isIn(BLUE.getRGB(), GREEN.getRGB());
        assertThat(decode(stored(userId, "thumbnail")).getRGB(32, 32))
                .as("the thumbnail comes from the same upload as the image").isEqualTo(imageColour);
    }

    @Test
    void corsLetsTheFrontendUploadAndDeleteAvatars() throws Exception {
        for (String method : List.of("PUT", "DELETE")) {
            HttpRequest request = HttpRequest.newBuilder(uri(OWN))
                    .header("Origin", "http://localhost:5173")
                    .header("Access-Control-Request-Method", method)
                    .header("Access-Control-Request-Headers", "authorization,content-type")
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build();
            var result = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(result.statusCode()).isEqualTo(200);
            assertThat(result.headers().firstValue("Access-Control-Allow-Methods").orElseThrow()).contains(method);
            assertThat(result.headers().firstValue("Access-Control-Allow-Origin").orElseThrow())
                    .isEqualTo("http://localhost:5173");
        }
    }

    private void assertEveryAvatarApiAnswers401(String bearer) throws Exception {
        assertThat(get(OWN, bearer).statusCode()).isEqualTo(401);
        assertThat(get("/api/v1/accounts/" + userId + "/avatar", bearer).statusCode()).isEqualTo(401);
        assertThat(upload(bearer, "b.png", png(64, 64, GREEN)).statusCode()).isEqualTo(401);
        assertThat(delete(bearer).statusCode()).isEqualTo(401);
    }

    private UUID account(String email, Set<Role> roles) {
        return accounts.saveAndFlush(new Account(email, "Avatar test", passwordHash, roles, START)).getId();
    }

    private String login(String email) throws Exception {
        byte[] body = json.writeValueAsBytes(Map.of("email", email, "password", PASSWORD));
        return expect(send("POST", "/api/v1/auth/login", null, "application/json", body), 200)
                .path("accessToken").asText();
    }

    private Jwt jwt(UUID subject, UUID session) {
        return Jwt.withTokenValue("service-test").header("alg", "HS256")
                .subject(subject.toString()).claim("jti", session.toString()).issuedAt(START)
                .expiresAt(START.plus(TokenService.ACCESS_TOKEN_TTL)).build();
    }

    // Hashes keep the comparison short and let Map.equals compare the pictures by content.
    private Map<String, Object> avatarState(UUID owner) {
        return jdbc.queryForMap("""
                SELECT content_type, md5(image) AS image, md5(thumbnail) AS thumbnail, size_bytes, updated_at
                FROM user_avatars WHERE user_id = ?
                """, owner);
    }

    private byte[] stored(UUID owner, String column) {
        if (!Set.of("image", "thumbnail").contains(column)) { throw new IllegalArgumentException("Unexpected column"); }
        return jdbc.queryForObject("SELECT " + column + " FROM user_avatars WHERE user_id = ?", byte[].class, owner);
    }

    private HttpResponse<byte[]> upload(String bearer, String fileName, byte[] content) throws Exception {
        return send("PUT", OWN, bearer, multipart("file", fileName, content));
    }

    private HttpResponse<byte[]> get(String path, String bearer) throws Exception {
        return send("GET", path, bearer, null, null);
    }

    private HttpResponse<byte[]> delete(String bearer) throws Exception {
        return send("DELETE", OWN, bearer, null, null);
    }

    private HttpResponse<byte[]> send(String method, String path, String bearer, Multipart body) throws Exception {
        return send(method, path, bearer, body.contentType(), body.content());
    }

    private HttpResponse<byte[]> send(String method, String path, String bearer, String contentType, byte[] content)
            throws Exception {
        var builder = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(30)).method(method,
                content == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(content));
        if (contentType != null) { builder.header("Content-Type", contentType); }
        if (bearer != null) { builder.header("Authorization", "Bearer " + bearer); }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + environment.getRequiredProperty("local.server.port") + path);
    }

    // A multipart/form-data body with one file field, built by hand because java.net.http has no helper for it.
    // The declared Content-Type is always octet-stream: the server must recognise the picture from its bytes.
    private static Multipart multipart(String field, String fileName, byte[] content) {
        String boundary = "ttcs-" + UUID.randomUUID();
        var body = new ByteArrayOutputStream();
        body.writeBytes(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + field + "\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(content);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return new Multipart("multipart/form-data; boundary=" + boundary, body.toByteArray());
    }

    private record Multipart(String contentType, byte[] content) { }

    private static MockMultipartFile file(byte[] content) {
        return new MockMultipartFile("file", "a.png", "image/png", content);
    }

    private JsonNode expect(HttpResponse<byte[]> response, int expected) {
        assertThat(response.statusCode()).as(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo(expected);
        return json.readTree(response.body());
    }

    private void error(HttpResponse<byte[]> response, int status, String code) {
        JsonNode body = expect(response, status);
        assertThat(body.path("code").asText()).isEqualTo(code);
        assertThat(body.path("message").asText()).isNotBlank();
        noStore(response);
    }

    private void assertForbidden(HttpResponse<byte[]> response) {
        error(response, 403, "FORBIDDEN");
    }

    private static void noStore(HttpResponse<?> response) {
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
    }

    private static void assertSquareOf(BufferedImage image, int side, Color colour) {
        assertThat(image.getWidth()).isEqualTo(side);
        assertThat(image.getHeight()).isEqualTo(side);
        for (int[] point : new int[][] {{0, 0}, {side - 1, 0}, {0, side - 1}, {side - 1, side - 1}, {side / 2, side / 2}}) {
            assertThat(image.getRGB(point[0], point[1])).as("pixel %d,%d", point[0], point[1]).isEqualTo(colour.getRGB());
        }
    }

    // JPEG is lossy, so its colours are compared with a small tolerance.
    private static void assertCloseTo(Color expected, int rgb) {
        Color actual = new Color(rgb);
        assertThat(actual.getRed()).isBetween(expected.getRed() - 12, expected.getRed() + 12);
        assertThat(actual.getGreen()).isBetween(expected.getGreen() - 12, expected.getGreen() + 12);
        assertThat(actual.getBlue()).isBetween(expected.getBlue() - 12, expected.getBlue() + 12);
    }

    private static BufferedImage filled(int width, int height, Color colour) {
        BufferedImage image = new BufferedImage(width, height, TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(colour);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        return image;
    }

    private static byte[] png(int width, int height, Color colour) {
        return encode(filled(width, height, colour), "png");
    }

    private static byte[] encode(BufferedImage image, String format) {
        var output = new ByteArrayOutputStream();
        try (var stream = new MemoryCacheImageOutputStream(output)) {
            if (!ImageIO.write(image, format, stream)) {
                throw new IllegalStateException("No ImageIO writer for " + format);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return output.toByteArray();
    }

    private static BufferedImage decode(byte[] content) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(content));
        assertThat(image).as("stored bytes must be a readable picture").isNotNull();
        return image;
    }

    // A valid PNG of exactly totalSize bytes: a private chunk ("paDd") that decoders skip fills the rest.
    private static byte[] pngOfExactly(int totalSize) {
        byte[] png = png(64, 64, BLUE);
        return withPngChunk(png, "paDd", new byte[totalSize - png.length - 12]);
    }

    // Inserts one chunk right after the 8-byte signature and the 25-byte IHDR chunk.
    private static byte[] withPngChunk(byte[] png, String type, byte[] data) {
        int afterHeader = 8 + 25;
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        byte[] chunk = ByteBuffer.allocate(12 + data.length).putInt(data.length).put(typeBytes).put(data)
                .putInt((int) crc.getValue()).array();
        var output = new ByteArrayOutputStream();
        output.write(png, 0, afterHeader);
        output.writeBytes(chunk);
        output.write(png, afterHeader, png.length - afterHeader);
        return output.toByteArray();
    }

    private static String latin1(byte[] content) {
        return new String(content, StandardCharsets.ISO_8859_1);
    }

    private int lockAccount(Connection connection, UUID id) throws Exception {
        try (var statement = connection.prepareStatement("SELECT pg_backend_pid() FROM user_accounts WHERE id = ? FOR UPDATE")) {
            statement.setObject(1, id);
            try (var row = statement.executeQuery()) { assertThat(row.next()).isTrue(); return row.getInt(1); }
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
