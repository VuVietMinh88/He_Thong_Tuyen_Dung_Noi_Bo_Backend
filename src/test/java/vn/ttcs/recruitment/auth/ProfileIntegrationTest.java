package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
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
import vn.ttcs.recruitment.account.profile.ProfileService;
import vn.ttcs.recruitment.account.profile.ProfileUpdateRequest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=false",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProfileIntegrationTest {
    private static final String PASSWORD = "TestingOnly123!";
    private static final String EMAIL = "lap@example.test";
    private static final Instant START = Instant.parse("2026-10-05T00:00:00Z");

    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccountRepository accounts;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ProfileService profiles;
    @Autowired private AuthIntegrationTest.MutableClock clock;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private UUID userId;
    private UUID otherId;
    private UUID sessionId;
    private String token;

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
        jdbc.update("UPDATE user_accounts SET department_id=NULL");
        jdbc.update("DELETE FROM departments");
        jdbc.update("DELETE FROM user_accounts");
        jdbc.update("""
                INSERT INTO role_permissions (role_code, permission_code)
                SELECT code, 'SELF_PROFILE_READ' FROM roles WHERE internal
                UNION ALL SELECT code, 'SELF_PROFILE_WRITE' FROM roles WHERE internal
                ON CONFLICT DO NOTHING
                """);
        String hash = passwordEncoder.encode(PASSWORD);
        userId = accounts.saveAndFlush(new Account(EMAIL, "Lâm Duy Lập", hash, Set.of(Role.RECRUITER), START)).getId();
        otherId = accounts.saveAndFlush(new Account("other@example.test", "Người dùng khác", hash,
                Set.of(Role.INTERVIEWER), START)).getId();
        var login = request("POST", "/api/v1/auth/login",
                json.writeValueAsString(Map.of("email", EMAIL, "password", PASSWORD)), null);
        assertThat(login.statusCode()).isEqualTo(200);
        token = body(login).path("accessToken").asText();
        sessionId = jdbc.queryForObject("SELECT id FROM auth_sessions WHERE user_id = ?", UUID.class, userId);
    }

    @Test
    void readsOwnSafeProfileEvenWhenQueryContainsAnotherUserId() throws Exception {
        var result = request("GET", "/api/v1/profile?userId=" + otherId, null, token);
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        JsonNode profile = body(result);
        assertThat(profile.path("id").asText()).isEqualTo(userId.toString());
        assertThat(profile.path("email").asText()).isEqualTo(EMAIL);
        assertThat(profile.path("fullName").asText()).isEqualTo("Lâm Duy Lập");
        assertThat(profile.path("roles").toString()).isEqualTo("[\"RECRUITER\"]");
        assertThat(profile.path("departmentId").isNull()).isTrue();
        assertThat(result.body()).doesNotContain("password", "token", "Token", "failedLogin", "lockedUntil", "enabled");
    }

    @Test
    void updatesAllowedUnicodeFieldsWithoutChangingIdentitySecurityOrAnotherAccount() throws Exception {
        Map<String, Object> securityBefore = securityFields(userId);
        Map<String, Object> otherBefore = jdbc.queryForMap("SELECT * FROM user_accounts WHERE id=?", otherId);
        var result = put(Map.of("fullName", "  Lâm Duy Lập mới  ", "phone", " +84912345678 ",
                "displayTitle", "  Chuyên viên tuyển dụng  "));
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(body(result).path("fullName").asText()).isEqualTo("Lâm Duy Lập mới");
        assertThat(body(result).path("phone").asText()).isEqualTo("0912345678");
        assertThat(body(result).path("displayTitle").asText()).isEqualTo("Chuyên viên tuyển dụng");
        assertThat(body(request("GET", "/api/v1/profile", null, token))).isEqualTo(body(result));
        assertThat(accounts.findById(userId).orElseThrow().getFullName()).isEqualTo("Lâm Duy Lập mới");
        assertThat(securityFields(userId)).isEqualTo(securityBefore);
        assertThat(jdbc.queryForMap("SELECT * FROM user_accounts WHERE id=?", otherId)).isEqualTo(otherBefore);
        assertThat(accounts.findById(userId).orElseThrow().getRoles()).containsExactly(Role.RECRUITER);
        assertThat(jdbc.queryForObject("SELECT revoked_at IS NULL FROM auth_sessions WHERE id=?", Boolean.class, sessionId))
                .isTrue();
    }

    @Test
    void returnsAssignedDepartmentWithoutAllowingSelfTransfer() throws Exception {
        UUID departmentId = UUID.randomUUID();
        jdbc.update("INSERT INTO departments (id,code,name,manager_user_id) VALUES (?,?,?,?)",
                departmentId, "HR", "Phòng nhân sự", otherId);
        jdbc.update("UPDATE user_accounts SET department_id=? WHERE id=?", departmentId, userId);
        var result = request("GET", "/api/v1/profile", null, token);
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(body(result).path("departmentId").asText()).isEqualTo(departmentId.toString());
        assertThat(body(result).path("departmentName").asText()).isEqualTo("Phòng nhân sự");
        var update = put(Map.of("fullName", "Lâm Duy Lập mới"));
        assertThat(update.statusCode()).isEqualTo(200);
        assertThat(body(update).path("departmentId").asText()).isEqualTo(departmentId.toString());
        assertThat(body(update).path("departmentName").asText()).isEqualTo("Phòng nhân sự");
        assertThat(accounts.findById(userId).orElseThrow().getDepartmentId()).isEqualTo(departmentId);
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void everyInternalRoleCanReadAndUpdateOnlyItsOwnProfile(Role role) throws Exception {
        jdbc.update("UPDATE user_roles SET role=? WHERE user_id=?", role.name(), userId);
        assertThat(request("GET", "/api/v1/profile", null, token).statusCode()).isEqualTo(200);
        assertThat(put(Map.of("fullName", "Hồ sơ " + role.name())).statusCode()).isEqualTo(200);
        assertThat(accounts.findById(otherId).orElseThrow().getFullName()).isEqualTo("Người dùng khác");
    }

    @ParameterizedTest
    @ValueSource(strings = {"email", "departmentId", "departmentName", "roles", "userId", "id", "enabled", "passwordHash"})
    void rejectsEveryForbiddenFieldWithoutPartialUpdate(String field) throws Exception {
        Map<String, Object> before = jdbc.queryForMap("SELECT * FROM user_accounts WHERE id=?", userId);
        var response = put(Map.of("fullName", "Không được lưu", field, "forbidden"));
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForMap("SELECT * FROM user_accounts WHERE id=?", userId)).isEqualTo(before);
        assertThat(accounts.findById(userId).orElseThrow().getRoles()).containsExactly(Role.RECRUITER);
    }

    @ParameterizedTest
    @CsvSource({"0912345678,0912345678", "0351234567,0351234567", "+84912345678,0912345678",
            "02412345678,02412345678", "+842812345678,02812345678"})
    void acceptsVietnameseMobileAndLandlineNumbers(String input, String expected) throws Exception {
        var result = put(Map.of("fullName", "Lâm Duy Lập", "phone", input));
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(body(result).path("phone").asText()).isEqualTo(expected);
        assertThat(accounts.findById(userId).orElseThrow().getPhone()).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0112345678", "091234567", "09123456789", "+840912345678", "+14155552671",
            "091 234 5678", "phone", "0241234567"})
    void invalidPhoneDoesNotPersistAnyField(String phone) throws Exception {
        var result = put(Map.of("fullName", "Không được lưu", "phone", phone));
        assertThat(result.statusCode()).isEqualTo(400);
        assertThat(body(result).path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(accounts.findById(userId).orElseThrow().getFullName()).isEqualTo("Lâm Duy Lập");
        assertThat(accounts.findById(userId).orElseThrow().getPhone()).isNull();
    }

    @Test
    void putCanClearOptionalFieldsAndEnforcesRequiredNameAndLengthLimits() throws Exception {
        assertThat(put(Map.of("fullName", "Hồ sơ", "phone", "0912345678", "displayTitle", "Chuyên viên"))
                .statusCode()).isEqualTo(200);
        var cleared = put(Map.of("fullName", "Hồ sơ", "phone", "  ", "displayTitle", "  "));
        assertThat(cleared.statusCode()).isEqualTo(200);
        assertThat(accounts.findById(userId).orElseThrow().getPhone()).isNull();
        assertThat(accounts.findById(userId).orElseThrow().getDisplayTitle()).isNull();
        for (Map<String, ?> invalid : List.<Map<String, ?>>of(Map.of(), Map.of("fullName", "  "),
                Map.of("fullName", "a".repeat(256)), Map.of("fullName", "Hồ sơ", "displayTitle", "x".repeat(121)))) {
            assertThat(put(invalid).statusCode()).isEqualTo(400);
        }
        assertThat(request("PUT", "/api/v1/profile", "{", token).statusCode()).isEqualTo(400);
        assertThat(accounts.findById(userId).orElseThrow().getFullName()).isEqualTo("Hồ sơ");
    }

    @Test
    void removesInvisibleCharactersAroundOwnNameAndTitleAndTreatsInvisibleOnesAsEmpty() throws Exception {
        // trim() kept these, so they were stored around a name or title, or alone as an invisible one: non-breaking
        // spaces (U+00A0, U+2007, U+202F, often pasted from Word or a web page), U+0085, U+180E, the ideographic and em
        // spaces, and zero-width characters (U+200B, U+FEFF, U+2060). The characters are built from code points, so the
        // source shows which one each case sends.
        for (int invisible : new int[]{0x00A0, 0x2007, 0x202F, 0x0085, 0x180E, 0x3000, 0x2003, 0x200B, 0xFEFF, 0x2060}) {
            String character = Character.toString(invisible);
            var result = put(Map.of("fullName", character + "Lâm Duy Lập" + character,
                    "displayTitle", character + "Chuyên viên" + character, "phone", character + "0912345678" + character));
            assertThat(result.statusCode()).as("U+%04X", invisible).isEqualTo(200);
            assertThat(body(result).path("fullName").asText()).as("U+%04X", invisible).isEqualTo("Lâm Duy Lập");
            assertThat(body(result).path("displayTitle").asText()).as("U+%04X", invisible).isEqualTo("Chuyên viên");
            assertThat(body(result).path("phone").asText()).as("U+%04X", invisible).isEqualTo("0912345678");
            assertThat(jdbc.queryForMap("SELECT full_name, display_title FROM user_accounts WHERE id = ?", userId))
                    .containsEntry("full_name", "Lâm Duy Lập").containsEntry("display_title", "Chuyên viên");
        }
        // Characters inside the name belong to it and are kept; the length limit counts the name without its ends.
        String nbsp = Character.toString(0x00A0);
        assertThat(body(put(Map.of("fullName", "Lâm" + nbsp + "Duy Lập"))).path("fullName").asText())
                .isEqualTo("Lâm" + nbsp + "Duy Lập");
        assertThat(body(put(Map.of("fullName", nbsp + "n".repeat(255) + nbsp))).path("fullName").asText())
                .isEqualTo("n".repeat(255));

        // Without a visible character the name counts as empty and is refused; the title is cleared.
        assertThat(put(Map.of("fullName", "Lâm Duy Lập", "displayTitle", "Chuyên viên")).statusCode()).isEqualTo(200);
        for (int[] codePoints : List.of(new int[]{0x00A0}, new int[]{0x2007, 0x202F}, new int[]{0x0085, 0x180E},
                new int[]{0x200B}, new int[]{0xFEFF, 0x00A0, 0x2060}, new int[]{0x3000, 0x200B})) {
            String invisible = new String(codePoints, 0, codePoints.length);
            Map<String, Object> before = jdbc.queryForMap("SELECT * FROM user_accounts WHERE id = ?", userId);
            var refused = put(Map.of("fullName", invisible, "displayTitle", "Chuyên viên"));
            assertThat(refused.statusCode()).as("%s", invisible.codePoints().mapToObj(Integer::toHexString).toList())
                    .isEqualTo(400);
            assertThat(body(refused).path("code").asText()).isEqualTo("VALIDATION_ERROR");
            assertThat(body(refused).path("fieldErrors").has("fullName")).isTrue();
            assertThat(jdbc.queryForMap("SELECT * FROM user_accounts WHERE id = ?", userId)).isEqualTo(before);

            var cleared = put(Map.of("fullName", "Lâm Duy Lập", "displayTitle", invisible));
            assertThat(cleared.statusCode()).isEqualTo(200);
            assertThat(body(cleared).path("displayTitle").isNull()).isTrue();
            assertThat(accounts.findById(userId).orElseThrow().getDisplayTitle()).isNull();
            assertThat(put(Map.of("fullName", "Lâm Duy Lập", "displayTitle", "Chuyên viên")).statusCode()).isEqualTo(200);
        }
    }

    @Test
    void removesEveryCharacterPostgresqlCountsAsASpaceAroundOwnNameAndTitle() {
        // The database is asked, not a fixed list: which characters [[:space:]] matches depends on its locale.
        List<Integer> spaces = jdbc.queryForList("""
                SELECT code_point FROM generate_series(1, 1114111) AS code_point
                WHERE code_point NOT BETWEEN 55296 AND 57343 AND chr(code_point) ~ '[[:space:]]'
                """, Integer.class);
        assertThat(spaces).contains(0x09, 0x20);
        for (int space : spaces) {
            String character = Character.toString(space);
            ProfileUpdateRequest request = new ProfileUpdateRequest(character + "Lâm Duy Lập" + character, null,
                    character + "Chuyên viên" + character);
            assertThat(request.fullName()).as("U+%04X", space).isEqualTo("Lâm Duy Lập");
            assertThat(request.displayTitle()).as("U+%04X", space).isEqualTo("Chuyên viên");
            ProfileUpdateRequest invisible = new ProfileUpdateRequest(character, null, character);
            assertThat(invisible.fullName()).as("U+%04X", space).isNull();
            assertThat(invisible.displayTitle()).as("U+%04X", space).isNull();
        }
    }

    @Test
    void requiresAuthenticationAndRefusesRevokedDisabledAndExpiredSessions() throws Exception {
        assertDeniedForBothMethods(null);
        jdbc.update("UPDATE auth_sessions SET revoked_at=? WHERE id=?", Timestamp.from(START), sessionId);
        assertDeniedForBothMethods(token);
        jdbc.update("UPDATE auth_sessions SET revoked_at=NULL WHERE id=?", sessionId);
        jdbc.update("UPDATE user_accounts SET enabled=false WHERE id=?", userId);
        assertDeniedForBothMethods(token);
        jdbc.update("UPDATE user_accounts SET enabled=true WHERE id=?", userId);
        // Keep expires_at > created_at, then move the clock exactly to the valid session's expiry.
        jdbc.update("UPDATE auth_sessions SET expires_at=? WHERE id=?", Timestamp.from(START.plusSeconds(1)), sessionId);
        clock.set(START.plusSeconds(1));
        assertDeniedForBothMethods(token);
        jdbc.update("UPDATE auth_sessions SET expires_at=? WHERE id=?", Timestamp.from(START.plusSeconds(86400)), sessionId);
        clock.set(START.plus(TokenService.ACCESS_TOKEN_TTL));
        assertDeniedForBothMethods(token);
        assertThat(accounts.findById(userId).orElseThrow().getFullName()).isEqualTo("Lâm Duy Lập");
    }

    @Test
    void livePermissionRemovalStopsBothHttpAndServiceCalls() throws Exception {
        jdbc.update("DELETE FROM role_permissions WHERE role_code='RECRUITER' AND permission_code='SELF_PROFILE_WRITE'");
        assertThat(put(Map.of("fullName", "Không được lưu")).statusCode()).isEqualTo(403);
        assertThatThrownBy(() -> profiles.update(jwt(userId, sessionId), new ProfileUpdateRequest("Không được lưu", null, null)))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(request("GET", "/api/v1/profile", null, token).statusCode()).isEqualTo(200);
        jdbc.update("DELETE FROM role_permissions WHERE role_code='RECRUITER' AND permission_code='SELF_PROFILE_READ'");
        assertThat(request("GET", "/api/v1/profile", null, token).statusCode()).isEqualTo(403);
        assertThatThrownBy(() -> profiles.get(jwt(userId, sessionId))).isInstanceOf(AccessDeniedException.class);
        assertThat(accounts.findById(userId).orElseThrow().getFullName()).isEqualTo("Lâm Duy Lập");
    }

    @Test
    void serviceRechecksSessionOwnershipRevocationAndTokenExpiryBeforeWriting() {
        var update = new ProfileUpdateRequest("Không được lưu", null, null);
        assertThatThrownBy(() -> profiles.update(jwt(otherId, sessionId), update))
                .isInstanceOf(AuthenticationFailureException.class);
        jdbc.update("UPDATE auth_sessions SET revoked_at=? WHERE id=?", Timestamp.from(START), sessionId);
        assertThatThrownBy(() -> profiles.update(jwt(userId, sessionId), update))
                .isInstanceOf(AuthenticationFailureException.class);
        jdbc.update("UPDATE auth_sessions SET revoked_at=NULL WHERE id=?", sessionId);
        clock.set(START.plus(TokenService.ACCESS_TOKEN_TTL));
        assertThatThrownBy(() -> profiles.update(jwt(userId, sessionId), update))
                .isInstanceOf(AuthenticationFailureException.class);
        assertThat(accounts.findById(userId).orElseThrow().getFullName()).isEqualTo("Lâm Duy Lập");
        assertThat(accounts.findById(otherId).orElseThrow().getFullName()).isEqualTo("Người dùng khác");
    }

    @Test
    void corsAllowsFrontendProfileUpdates() throws Exception {
        String base = "http://127.0.0.1:" + environment.getRequiredProperty("local.server.port");
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/api/v1/profile"))
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "PUT")
                .header("Access-Control-Request-Headers", "authorization,content-type")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build();
        var result = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.headers().firstValue("Access-Control-Allow-Methods").orElseThrow()).contains("PUT");
        assertThat(result.headers().firstValue("Access-Control-Allow-Origin").orElseThrow()).isEqualTo("http://localhost:5173");
    }

    private Map<String, Object> securityFields(UUID id) {
        return jdbc.queryForMap("""
                SELECT email, password_hash, enabled, failed_login_attempts, locked_until, department_id
                FROM user_accounts WHERE id=?
                """, id);
    }

    private Jwt jwt(UUID subject, UUID session) {
        return Jwt.withTokenValue("service-test").header("alg", "HS256")
                .subject(subject.toString()).claim("jti", session.toString()).issuedAt(START)
                .expiresAt(START.plus(TokenService.ACCESS_TOKEN_TTL)).build();
    }

    private void assertDeniedForBothMethods(String bearer) throws Exception {
        assertThat(request("GET", "/api/v1/profile", null, bearer).statusCode()).isEqualTo(401);
        assertThat(request("PUT", "/api/v1/profile", "{\"fullName\":\"Không được lưu\"}", bearer).statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> put(Map<String, ?> content) throws Exception {
        return request("PUT", "/api/v1/profile", json.writeValueAsString(content), token);
    }

    private HttpResponse<String> request(String method, String path, String content, String bearer) throws Exception {
        String base = "http://127.0.0.1:" + environment.getRequiredProperty("local.server.port");
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json").method(method,
                        content == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(content));
        if (bearer != null) { request.header("Authorization", "Bearer " + bearer); }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode body(HttpResponse<String> response) { return json.readTree(response.body()); }
}
