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
class RecruitmentCatalogManagementIntegrationTest {
    private static final String BASE = "/api/v1/recruitment-catalogs/";
    private static final String SOURCES = BASE + "CANDIDATE_SOURCE/items";
    private static final String REASONS = BASE + "REJECTION_REASON/items";
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
        jdbc.update("DELETE FROM recruitment_catalog_items");
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
    void createsTrimmedValuesAtTheEndOfTheirOwnCatalogAndReadsThemBack() throws Exception {
        var empty = get(SOURCES, adminToken);
        assertThat(expect(empty, 200).isArray()).isTrue();
        assertThat(expect(empty, 200).isEmpty()).isTrue();
        noStore(empty);

        var response = create(SOURCES, payload("  LINKEDIN  ", "  LinkedIn  ", true), adminToken);
        JsonNode linkedIn = expect(response, 201);
        noStore(response);
        UUID id = UUID.fromString(linkedIn.path("id").asText());
        assertThat(linkedIn.size()).isEqualTo(8);
        assertThat(linkedIn.path("type").asText()).isEqualTo("CANDIDATE_SOURCE");
        assertThat(linkedIn.path("code").asText()).isEqualTo("LINKEDIN");
        assertThat(linkedIn.path("name").asText()).isEqualTo("LinkedIn");
        assertThat(linkedIn.path("sortOrder").asInt()).isZero();
        assertThat(linkedIn.path("active").asBoolean()).isTrue();
        assertThat(Instant.parse(linkedIn.path("createdAt").asText())).isEqualTo(START);
        assertThat(Instant.parse(linkedIn.path("updatedAt").asText())).isEqualTo(START);

        JsonNode referral = expect(create(SOURCES, payload("REFERRAL", "Nhân viên giới thiệu", true), adminToken), 201);
        JsonNode old = expect(create(SOURCES, payload("JOB_FAIR", "Ngày hội việc làm", false), adminToken), 201);
        assertThat(referral.path("sortOrder").asInt()).isEqualTo(1);
        assertThat(old.path("sortOrder").asInt()).isEqualTo(2);
        assertThat(old.path("active").asBoolean()).isFalse();
        // Each catalog type has its own order, starting from 0.
        JsonNode reason = expect(create(REASONS, payload("SKILL_MISMATCH", "Chưa phù hợp kỹ năng", true), adminToken), 201);
        assertThat(reason.path("type").asText()).isEqualTo("REJECTION_REASON");
        assertThat(reason.path("sortOrder").asInt()).isZero();

        var detail = get(SOURCES + "/" + id, adminToken);
        assertThat(expect(detail, 200)).isEqualTo(linkedIn);
        noStore(detail);
        assertThat(ids(expect(get(SOURCES, adminToken), 200))).containsExactly(
                id.toString(), referral.path("id").asText(), old.path("id").asText());
        assertThat(ids(expect(get(REASONS, adminToken), 200))).containsExactly(reason.path("id").asText());

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM recruitment_catalog_items WHERE id = ?", id);
        assertThat(row.get("catalog_type")).isEqualTo("CANDIDATE_SOURCE");
        assertThat(row.get("code")).isEqualTo("LINKEDIN");
        assertThat(row.get("name")).isEqualTo("LinkedIn");
        assertThat(row.get("sort_order")).isEqualTo(0);
        assertThat(row.get("active")).isEqualTo(true);
        assertThat(row.get("created_at")).isEqualTo(Timestamp.from(START));
    }

    @Test
    void listsByDisplayOrderThenCodeFiltersByActiveAndAppendsAfterTheLargestOrder() throws Exception {
        // The API cannot set sortOrder yet, so rows with chosen orders are written directly.
        UUID second = insertItem("CANDIDATE_SOURCE", "TOPCV", "TopCV", 1, true);
        UUID first = insertItem("CANDIDATE_SOURCE", "REFERRAL", "Giới thiệu", 1, true);
        UUID top = insertItem("CANDIDATE_SOURCE", "WEBSITE", "Website công ty", 0, true);
        UUID inactive = insertItem("CANDIDATE_SOURCE", "JOB_FAIR", "Ngày hội việc làm", 5, false);
        insertItem("WORK_LOCATION", "HANOI", "Hà Nội", 0, true);

        // Equal sortOrder values (REFERRAL, TOPCV) are ordered by code.
        assertThat(ids(expect(get(SOURCES, adminToken), 200))).containsExactly(
                top.toString(), first.toString(), second.toString(), inactive.toString());
        assertThat(ids(expect(get(SOURCES + "?active=true", adminToken), 200))).containsExactly(
                top.toString(), first.toString(), second.toString());
        assertThat(ids(expect(get(SOURCES + "?active=false", adminToken), 200))).containsExactly(inactive.toString());
        assertThat(expect(get(BASE + "EMPLOYMENT_TYPE/items", adminToken), 200).isEmpty()).isTrue();

        // The end of the catalog is after the largest order, inactive values included.
        JsonNode appended = expect(create(SOURCES, payload("FACEBOOK", "Facebook", true), adminToken), 201);
        assertThat(appended.path("sortOrder").asInt()).isEqualTo(6);
        assertThat(ids(expect(get(SOURCES + "?active=true", adminToken), 200)))
                .last().isEqualTo(appended.path("id").asText());
        JsonNode location = expect(create(BASE + "WORK_LOCATION/items", payload("HCM", "TP. Hồ Chí Minh", true),
                adminToken), 201);
        assertThat(location.path("sortOrder").asInt()).isEqualTo(1);
    }

    @Test
    void updatesCodeNameAndActiveKeepingTypeOrderAndCreationTime() throws Exception {
        expect(create(SOURCES, payload("FIRST", "First", true), adminToken), 201);
        UUID id = item(SOURCES, "LINKEDIN", "LinkedIn", true);

        // The clock has nanoseconds; the response must show the microseconds PostgreSQL actually stores.
        clock.set(START.plusSeconds(60).plusNanos(123_456_789));
        var response = update(SOURCES, id, payload("  LINKEDIN_JOBS  ", "  LinkedIn Jobs  ", false), adminToken);
        JsonNode result = expect(response, 200);
        noStore(response);
        assertThat(result.path("id").asText()).isEqualTo(id.toString());
        assertThat(result.path("type").asText()).isEqualTo("CANDIDATE_SOURCE");
        assertThat(result.path("code").asText()).isEqualTo("LINKEDIN_JOBS");
        assertThat(result.path("name").asText()).isEqualTo("LinkedIn Jobs");
        assertThat(result.path("active").asBoolean()).isFalse();
        assertThat(result.path("sortOrder").asInt()).isEqualTo(1);
        assertThat(Instant.parse(result.path("createdAt").asText())).isEqualTo(START);
        assertThat(Instant.parse(result.path("updatedAt").asText()))
                .isEqualTo(START.plusSeconds(60).plusNanos(123_456_000));
        assertThat(expect(get(SOURCES + "/" + id, adminToken), 200)).isEqualTo(result);

        // Turning the value back on keeps its place in the list.
        assertThat(expect(update(SOURCES, id, payload("LINKEDIN_JOBS", "LinkedIn Jobs", true), adminToken), 200)
                .path("sortOrder").asInt()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void allInternalRolesCanReadButOnlyAdminAndHrManagerCanWriteByDefault(Role role) throws Exception {
        account("role@example.test", Set.of(role));
        String token = login("role@example.test").path("accessToken").asText();
        UUID target = item(SOURCES, "READ", "Read value", true);
        assertThat(ids(expect(get(SOURCES, token), 200))).containsExactly(target.toString());
        assertThat(expect(get(SOURCES + "/" + target, token), 200).path("code").asText()).isEqualTo("READ");

        boolean writer = role == Role.ADMIN || role == Role.HR_MANAGER;
        var created = create(SOURCES, payload("NEW", "New value", true), token);
        var updated = update(SOURCES, target, payload("READ", "Updated", false), token);
        if (writer) {
            expect(created, 201);
            assertThat(expect(updated, 200).path("name").asText()).isEqualTo("Updated");
        } else {
            error(created, 403, "FORBIDDEN");
            error(updated, 403, "FORBIDDEN");
            assertThat(count()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT name FROM recruitment_catalog_items WHERE id = ?", String.class,
                    target)).isEqualTo("Read value");
        }
    }

    @Test
    void rejectsMissingBlankOversizedAndUnknownFieldsButAcceptsLengthBoundaries() throws Exception {
        Map<String, Object> valid = payload("VALID", "Valid", true);
        for (String field : List.of("code", "name", "active")) {
            Map<String, Object> missing = new LinkedHashMap<>(valid);
            missing.remove(field);
            JsonNode body = expect(create(SOURCES, missing, adminToken), 400);
            assertThat(body.path("code").asText()).isEqualTo("VALIDATION_ERROR");
            assertThat(body.path("fieldErrors").has(field)).as(field).isTrue();
        }
        for (String field : List.of("code", "name")) {
            Map<String, Object> invalid = new LinkedHashMap<>(valid);
            invalid.put(field, " \t ");
            JsonNode blank = expect(create(SOURCES, invalid, adminToken), 400);
            assertThat(blank.path("fieldErrors").has(field)).as(field).isTrue();
            invalid.put(field, "x".repeat(field.equals("name") ? 256 : 51));
            JsonNode oversized = expect(create(SOURCES, invalid, adminToken), 400);
            assertThat(oversized.path("fieldErrors").has(field)).as(field).isTrue();
        }
        // The type comes from the URL and the server sets sortOrder, so neither is accepted in the body.
        Map<String, Object> extraFields = Map.of("sortOrder", 3, "type", "REJECTION_REASON",
                "id", UUID.randomUUID().toString());
        for (var extra : extraFields.entrySet()) {
            Map<String, Object> unknown = new LinkedHashMap<>(valid);
            unknown.put(extra.getKey(), extra.getValue());
            error(create(SOURCES, unknown, adminToken), 400, "INVALID_JSON");
        }
        Map<String, Object> notABoolean = new LinkedHashMap<>(valid);
        notABoolean.put("active", "có");
        error(create(SOURCES, notABoolean, adminToken), 400, "INVALID_JSON");
        error(request("POST", SOURCES, "{\"code\":", adminToken), 400, "INVALID_JSON");
        assertThat(count()).isZero();

        UUID boundary = item(SOURCES, "c".repeat(50), "n".repeat(255), true);
        Map<String, Object> before = row(boundary);
        Map<String, Object> blankName = new LinkedHashMap<>(valid);
        blankName.put("name", "");
        error(update(SOURCES, boundary, blankName, adminToken), 400, "VALIDATION_ERROR");
        Map<String, Object> withOrder = new LinkedHashMap<>(valid);
        withOrder.put("sortOrder", 0);
        error(update(SOURCES, boundary, withOrder, adminToken), 400, "INVALID_JSON");
        assertThat(row(boundary)).isEqualTo(before);
    }

    @Test
    void unknownCatalogTypesReturnAClearNotFoundErrorWithoutWritingAnything() throws Exception {
        UUID existing = item(SOURCES, "KEEP", "Keep", true);
        Map<String, Object> before = row(existing);
        // Type names are the exact enum names; lower case, plural or other words are not catalog types.
        for (String type : List.of("UNKNOWN", "candidate_source", "candidate-sources", "Candidate_Source")) {
            String items = BASE + type + "/items";
            for (var response : List.of(get(items, adminToken), get(items + "/" + existing, adminToken),
                    create(items, payload("NEW", "New", true), adminToken),
                    update(items, existing, payload("NEW", "New", true), adminToken))) {
                JsonNode body = expect(response, 404);
                assertThat(body.path("code").asText()).as(type).isEqualTo("RECRUITMENT_CATALOG_TYPE_NOT_FOUND");
                assertThat(body.path("message").asText()).as(type).isEqualTo(
                        "Không có loại danh mục tuyển dụng này. Loại hợp lệ: "
                                + "CANDIDATE_SOURCE, REJECTION_REASON, WORK_LOCATION, EMPLOYMENT_TYPE.");
                noStore(response);
            }
        }
        // The body is validated before the service looks at the type, as the API contract documents.
        error(create(BASE + "UNKNOWN/items", Map.of(), adminToken), 400, "VALIDATION_ERROR");
        assertThat(count()).isEqualTo(1);
        assertThat(row(existing)).isEqualTo(before);
    }

    @Test
    void returnsNotFoundForUnknownIdsAndForIdsOfAnotherCatalogType() throws Exception {
        UUID source = item(SOURCES, "LINKEDIN", "LinkedIn", true);
        Map<String, Object> before = row(source);

        var missing = get(SOURCES + "/" + UUID.randomUUID(), adminToken);
        error(missing, 404, "RECRUITMENT_CATALOG_ITEM_NOT_FOUND");
        noStore(missing);
        error(update(SOURCES, UUID.randomUUID(), payload("NONE", "None", true), adminToken),
                404, "RECRUITMENT_CATALOG_ITEM_NOT_FOUND");
        // The id exists, but under CANDIDATE_SOURCE: a PUT cannot move it to another catalog.
        error(get(REASONS + "/" + source, adminToken), 404, "RECRUITMENT_CATALOG_ITEM_NOT_FOUND");
        error(update(REASONS, source, payload("LINKEDIN", "Moved", true), adminToken),
                404, "RECRUITMENT_CATALOG_ITEM_NOT_FOUND");
        assertThat(row(source)).isEqualTo(before);

        error(get(SOURCES + "/not-a-uuid", adminToken), 400, "VALIDATION_ERROR");
        error(get(SOURCES + "?active=maybe", adminToken), 400, "VALIDATION_ERROR");
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void codesAreUniqueInsideOneCatalogTypeOnlyAndCaseSensitive() throws Exception {
        UUID upper = item(SOURCES, "OTHER", "Nguồn khác", true);
        UUID lower = item(SOURCES, "other", "Viết thường", true);
        // The same code in another catalog type is a different value.
        item(REASONS, "OTHER", "Lý do khác", true);

        var duplicate = create(SOURCES, payload("OTHER", "Trùng mã", true), adminToken);
        JsonNode body = expect(duplicate, 409);
        assertThat(body.path("code").asText()).isEqualTo("RECRUITMENT_CATALOG_CODE_EXISTS");
        assertThat(body.path("message").asText()).isEqualTo("Mã giá trị đã được sử dụng trong danh mục này.");
        noStore(duplicate);
        error(create(SOURCES, payload("  OTHER  ", "Trùng mã có khoảng trắng", true), adminToken),
                409, "RECRUITMENT_CATALOG_CODE_EXISTS");

        Map<String, Object> before = row(lower);
        error(update(SOURCES, lower, payload("OTHER", "Đổi sang mã trùng", true), adminToken),
                409, "RECRUITMENT_CATALOG_CODE_EXISTS");
        assertThat(row(lower)).isEqualTo(before);

        // Keeping its own code is not a conflict.
        assertThat(expect(update(SOURCES, upper, payload("OTHER", "Nguồn khác (cập nhật)", true), adminToken), 200)
                .path("name").asText()).isEqualTo("Nguồn khác (cập nhật)");
        assertThat(count()).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"create", "update"})
    void codeCommittedByAnotherTransactionWhileTheWriteWaitsReturnsConflict(String operation) throws Exception {
        UUID existing = item(SOURCES, "OLD", "Old", true);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            // Uncommitted, so the API's existence check misses it and only the unique constraint can stop the write.
            int blockerPid = insertUncommittedItem(connection, "SAME");
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(() -> operation.equals("create")
                        ? create(SOURCES, payload("SAME", "Second", true), adminToken)
                        : update(SOURCES, existing, payload("SAME", "Renamed", true), adminToken));
                try {
                    awaitWaiters(blockerPid, 1);
                    connection.commit();
                    error(response.get(10, TimeUnit.SECONDS), 409, "RECRUITMENT_CATALOG_CODE_EXISTS");
                } finally {
                    connection.rollback();
                }
            }
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recruitment_catalog_items WHERE code = 'SAME'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT code FROM recruitment_catalog_items WHERE id = ?", String.class,
                existing)).isEqualTo("OLD");
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
                var response = executor.submit(() -> create(SOURCES, payload("DENIED", "Denied", true), adminToken));
                try {
                    awaitWaiters(blockerPid, 1);
                    switch (change) {
                        case "lost-permission" -> jdbc.update("DELETE FROM role_permissions "
                                + "WHERE role_code = 'ADMIN' AND permission_code = 'ORGANIZATION_WRITE_ALL'");
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
                    VALUES ('ADMIN', 'ORGANIZATION_WRITE_ALL')
                    ON CONFLICT DO NOTHING
                    """);
        }
        assertThat(count()).isZero();
    }

    private UUID account(String email, Set<Role> roles) {
        return accounts.saveAndFlush(new Account(email, "Catalog test", fixturePasswordHash, roles, START)).getId();
    }

    private UUID item(String items, String code, String name, boolean active) throws Exception {
        return UUID.fromString(expect(create(items, payload(code, name, active), adminToken), 201).path("id").asText());
    }

    private UUID insertItem(String type, String code, String name, int sortOrder, boolean active) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO recruitment_catalog_items (id,catalog_type,code,name,sort_order,active,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, id, type, code, name, sortOrder, active, Timestamp.from(START), Timestamp.from(START));
        return id;
    }

    private Map<String, Object> payload(String code, String name, boolean active) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", code);
        result.put("name", name);
        result.put("active", active);
        return result;
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT * FROM recruitment_catalog_items WHERE id = ?", id);
    }

    private int count() {
        return jdbc.queryForObject("SELECT count(*) FROM recruitment_catalog_items", Integer.class);
    }

    private JsonNode login(String email) throws Exception {
        return expect(request("POST", "/api/v1/auth/login",
                json.writeValueAsString(Map.of("email", email, "password", PASSWORD)), null), 200);
    }

    private HttpResponse<String> create(String items, Map<String, Object> payload, String token) throws Exception {
        return request("POST", items, json.writeValueAsString(payload), token);
    }

    private HttpResponse<String> update(String items, UUID id, Map<String, Object> payload, String token)
            throws Exception {
        return request("PUT", items + "/" + id, json.writeValueAsString(payload), token);
    }

    private HttpResponse<String> get(String path, String token) throws Exception { return request("GET", path, null, token); }

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

    private List<String> ids(JsonNode array) {
        List<String> ids = new ArrayList<>();
        array.forEach(item -> ids.add(item.path("id").asText()));
        return ids;
    }

    private int insertUncommittedItem(Connection connection, String code) throws Exception {
        execute(connection, """
                INSERT INTO recruitment_catalog_items (id,catalog_type,code,name,sort_order,active,created_at,updated_at)
                VALUES (?, 'CANDIDATE_SOURCE', ?, 'Other transaction', 0, TRUE, ?, ?)
                """, UUID.randomUUID(), code, Timestamp.from(START), Timestamp.from(START));
        return backendPid(connection);
    }

    private int lockAccount(Connection connection, UUID id) throws Exception {
        try (var statement = connection.prepareStatement("SELECT id FROM user_accounts WHERE id = ? FOR UPDATE")) {
            statement.setObject(1, id);
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
