package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import java.util.concurrent.Callable;
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
    private static final String SOURCES_ORDER = BASE + "CANDIDATE_SOURCE/order";
    private static final String PASSWORD = "TestingOnly123!";
    private static final Instant START = Instant.parse("2026-10-07T00:00:00Z");
    // Test-only table that references catalog values, created by the delete tests and dropped after each test.
    private static final String REFERENCES = "catalog_item_test_references";

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

    @AfterEach
    void dropReferenceTable() {
        jdbc.execute("DROP TABLE IF EXISTS " + REFERENCES);
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
    void listsByDisplayOrderThenNameFiltersByActiveAndAppendsAfterTheLargestOrder() throws Exception {
        // Equal orders only come from two creates at the same moment, so these rows are written directly.
        // The names sort the other way round from the codes, so the test sees which column breaks the tie.
        UUID topCv = insertItem("CANDIDATE_SOURCE", "TOPCV", "TopCV", 1, true);
        UUID zalo = insertItem("CANDIDATE_SOURCE", "ZALO", "Nhóm Zalo", 1, true);
        UUID staffReferral = insertItem("CANDIDATE_SOURCE", "REFERRAL_STAFF", "Giới thiệu", 2, true);
        UUID partnerReferral = insertItem("CANDIDATE_SOURCE", "REFERRAL_PARTNER", "Giới thiệu", 2, true);
        UUID top = insertItem("CANDIDATE_SOURCE", "WEBSITE", "Website công ty", 0, true);
        UUID inactive = insertItem("CANDIDATE_SOURCE", "JOB_FAIR", "Ngày hội việc làm", 5, false);
        insertItem("WORK_LOCATION", "HANOI", "Hà Nội", 0, true);

        // Equal sortOrder: "Nhóm Zalo" before "TopCV" by name; equal name too: REFERRAL_PARTNER first by code.
        assertThat(ids(expect(get(SOURCES, adminToken), 200))).containsExactlyElementsOf(
                strings(top, zalo, topCv, partnerReferral, staffReferral, inactive));
        assertThat(ids(expect(get(SOURCES + "?active=true", adminToken), 200))).containsExactlyElementsOf(
                strings(top, zalo, topCv, partnerReferral, staffReferral));
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
        var deleted = delete(SOURCES, target, token);
        if (writer) {
            UUID createdId = UUID.fromString(expect(created, 201).path("id").asText());
            assertThat(expect(updated, 200).path("name").asText()).isEqualTo("Updated");
            assertThat(deleted.statusCode()).as(deleted.body()).isEqualTo(204);
            assertThat(ids(expect(get(SOURCES, token), 200))).containsExactly(createdId.toString());
        } else {
            error(created, 403, "FORBIDDEN");
            error(updated, 403, "FORBIDDEN");
            error(deleted, 403, "FORBIDDEN");
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
                    update(items, existing, payload("NEW", "New", true), adminToken),
                    delete(items, existing, adminToken),
                    reorder(BASE + type + "/order", List.of(existing), adminToken))) {
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
        error(request("PUT", BASE + "UNKNOWN/order", "{}", adminToken), 400, "VALIDATION_ERROR");
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
        var missingDelete = delete(SOURCES, UUID.randomUUID(), adminToken);
        error(missingDelete, 404, "RECRUITMENT_CATALOG_ITEM_NOT_FOUND");
        noStore(missingDelete);
        // A DELETE through another catalog type must not remove the value either.
        error(delete(REASONS, source, adminToken), 404, "RECRUITMENT_CATALOG_ITEM_NOT_FOUND");
        assertThat(row(source)).isEqualTo(before);

        error(get(SOURCES + "/not-a-uuid", adminToken), 400, "VALIDATION_ERROR");
        error(request("DELETE", SOURCES + "/not-a-uuid", null, adminToken), 400, "VALIDATION_ERROR");
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

    @Test
    void deletesAValueNothingUsesAndFreesItsCode() throws Exception {
        UUID first = item(SOURCES, "FIRST", "First", true);
        UUID middle = item(SOURCES, "MIDDLE", "Middle", false);
        UUID last = item(SOURCES, "LAST", "Last", true);
        UUID reason = item(REASONS, "MIDDLE", "Cùng mã, danh mục khác", true);

        var response = delete(SOURCES, middle, adminToken);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(204);
        assertThat(response.body()).isEmpty();
        noStore(response);

        error(get(SOURCES + "/" + middle, adminToken), 404, "RECRUITMENT_CATALOG_ITEM_NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recruitment_catalog_items WHERE id = ?", Integer.class,
                middle)).isZero();
        // The other values keep their display order, and the same code in another catalog type stays.
        JsonNode remaining = expect(get(SOURCES, adminToken), 200);
        assertThat(ids(remaining)).containsExactly(first.toString(), last.toString());
        assertThat(remaining.get(1).path("sortOrder").asInt()).isEqualTo(2);
        assertThat(ids(expect(get(REASONS, adminToken), 200))).containsExactly(reason.toString());

        // Deleting twice finds nothing the second time; the freed code can be used again.
        error(delete(SOURCES, middle, adminToken), 404, "RECRUITMENT_CATALOG_ITEM_NOT_FOUND");
        JsonNode reused = expect(create(SOURCES, payload("MIDDLE", "Middle again", true), adminToken), 201);
        assertThat(reused.path("id").asText()).isNotEqualTo(middle.toString());
        assertThat(reused.path("sortOrder").asInt()).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"NO ACTION", "RESTRICT"})
    void refusesToDeleteAValueThatAnotherTableStillReferences(String onDelete) throws Exception {
        UUID used = item(SOURCES, "LINKEDIN", "LinkedIn", true);
        UUID unused = item(SOURCES, "TOPCV", "TopCV", true);
        createReferenceTable(onDelete);
        UUID reference = UUID.randomUUID();
        jdbc.update("INSERT INTO " + REFERENCES + " (id, catalog_item_id) VALUES (?, ?)", reference, used);
        Map<String, Object> before = row(used);

        var response = delete(SOURCES, used, adminToken);
        JsonNode body = expect(response, 409);
        assertThat(body.path("code").asText()).isEqualTo("RECRUITMENT_CATALOG_ITEM_IN_USE");
        assertThat(body.path("message").asText()).isEqualTo("Giá trị danh mục đang được dữ liệu khác sử dụng nên "
                + "không thể xóa. Hãy chuyển giá trị sang ngừng sử dụng (active = false).");
        noStore(response);
        assertThat(row(used)).isEqualTo(before);
        assertThat(referencesTo(used)).isEqualTo(1);

        // Other values of the same catalog are still deleted normally.
        assertThat(delete(SOURCES, unused, adminToken).statusCode()).isEqualTo(204);
        // The message points to the supported way of retiring a value that is in use.
        assertThat(expect(update(SOURCES, used, payload("LINKEDIN", "LinkedIn", false), adminToken), 200)
                .path("active").asBoolean()).isFalse();
        // Being inactive does not make a referenced value deletable.
        error(delete(SOURCES, used, adminToken), 409, "RECRUITMENT_CATALOG_ITEM_IN_USE");
        assertThat(referencesTo(used)).isEqualTo(1);

        // Once the last reference is gone, the value can be deleted.
        jdbc.update("DELETE FROM " + REFERENCES + " WHERE id = ?", reference);
        assertThat(delete(SOURCES, used, adminToken).statusCode()).isEqualTo(204);
        assertThat(count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void referenceSavedByAnotherTransactionWhileTheDeleteWaitsDecidesTheResult(boolean referenceCommitted)
            throws Exception {
        UUID target = item(SOURCES, "LINKEDIN", "LinkedIn", true);
        createReferenceTable("NO ACTION");
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            // The uncommitted reference keeps a lock on the catalog row, so the DELETE has to wait for it.
            execute(connection, "INSERT INTO " + REFERENCES + " (id, catalog_item_id) VALUES (?, ?)",
                    UUID.randomUUID(), target);
            int blockerPid = backendPid(connection);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(() -> delete(SOURCES, target, adminToken));
                try {
                    awaitWaiters(blockerPid, 1);
                    if (referenceCommitted) {
                        connection.commit();
                        error(response.get(10, TimeUnit.SECONDS), 409, "RECRUITMENT_CATALOG_ITEM_IN_USE");
                    } else {
                        connection.rollback();
                        var result = response.get(10, TimeUnit.SECONDS);
                        assertThat(result.statusCode()).as(result.body()).isEqualTo(204);
                    }
                } finally {
                    connection.rollback();
                }
            }
        }
        assertThat(count()).isEqualTo(referenceCommitted ? 1 : 0);
        assertThat(referencesTo(target)).isEqualTo(referenceCommitted ? 1 : 0);
    }

    @Test
    void deleteRechecksThePermissionAfterWaitingForTheActorAccountLock() throws Exception {
        UUID target = item(SOURCES, "KEEP", "Keep", true);
        Map<String, Object> before = row(target);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blockerPid = lockAccount(connection, adminId);
            try (var executor = Executors.newSingleThreadExecutor()) {
                // The request passed the URL rule already; the permission disappears while it waits.
                var response = executor.submit(() -> delete(SOURCES, target, adminToken));
                try {
                    awaitWaiters(blockerPid, 1);
                    jdbc.update("DELETE FROM role_permissions "
                            + "WHERE role_code = 'ADMIN' AND permission_code = 'ORGANIZATION_WRITE_ALL'");
                    connection.commit();
                    error(response.get(10, TimeUnit.SECONDS), 403, "FORBIDDEN");
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
        assertThat(row(target)).isEqualTo(before);
    }

    @Test
    void savesTheNewOrderOfEveryValueOfOneCatalogAndListsThemInThatOrder() throws Exception {
        UUID linkedIn = item(SOURCES, "LINKEDIN", "LinkedIn", true);
        UUID referral = item(SOURCES, "REFERRAL", "Nhân viên giới thiệu", true);
        UUID jobFair = item(SOURCES, "JOB_FAIR", "Ngày hội việc làm", false);
        UUID reason = item(REASONS, "SKILL_MISMATCH", "Chưa phù hợp kỹ năng", true);
        Map<String, Object> reasonBefore = row(reason);

        clock.set(START.plusSeconds(60));
        // Inactive values are part of the order too, so they keep their place when turned back on.
        var response = reorder(SOURCES_ORDER, List.of(jobFair, referral, linkedIn), adminToken);
        JsonNode saved = expect(response, 200);
        noStore(response);
        assertThat(ids(saved)).containsExactlyElementsOf(strings(jobFair, referral, linkedIn));
        assertThat(sortOrders(saved)).containsExactly(0, 1, 2);
        assertThat(saved.get(0).path("code").asText()).isEqualTo("JOB_FAIR");
        assertThat(saved.get(0).path("active").asBoolean()).isFalse();
        // REFERRAL keeps number 1, so only the two values that moved get a new updatedAt.
        assertThat(Instant.parse(saved.get(0).path("updatedAt").asText())).isEqualTo(START.plusSeconds(60));
        assertThat(Instant.parse(saved.get(1).path("updatedAt").asText())).isEqualTo(START);
        assertThat(Instant.parse(saved.get(2).path("updatedAt").asText())).isEqualTo(START.plusSeconds(60));
        assertThat(Instant.parse(saved.get(2).path("createdAt").asText())).isEqualTo(START);

        // Later reads return exactly what the reorder returned; the active filter keeps the same order.
        assertThat(expect(get(SOURCES, adminToken), 200)).isEqualTo(saved);
        assertThat(ids(expect(get(SOURCES + "?active=true", adminToken), 200)))
                .containsExactlyElementsOf(strings(referral, linkedIn));
        assertThat(row(jobFair).get("sort_order")).isEqualTo(0);
        assertThat(row(linkedIn).get("sort_order")).isEqualTo(2);
        // Each catalog type has its own order, so the rejection reason is untouched.
        assertThat(row(reason)).isEqualTo(reasonBefore);

        // A value created afterwards still goes to the end.
        JsonNode appended = expect(create(SOURCES, payload("FACEBOOK", "Facebook", true), adminToken), 201);
        assertThat(appended.path("sortOrder").asInt()).isEqualTo(3);
        assertThat(ids(expect(get(SOURCES, adminToken), 200))).last().isEqualTo(appended.path("id").asText());
    }

    @Test
    void renumbersGapsAndEqualOrdersAndIgnoresAnUnchangedOrder() throws Exception {
        // Gaps come from deletes and equal numbers from two creates at the same moment.
        UUID a = insertItem("CANDIDATE_SOURCE", "A", "A", 4, true);
        UUID b = insertItem("CANDIDATE_SOURCE", "B", "B", 4, true);
        UUID c = insertItem("CANDIDATE_SOURCE", "C", "C", 9, true);
        assertThat(ids(expect(get(SOURCES, adminToken), 200))).containsExactlyElementsOf(strings(a, b, c));

        JsonNode saved = expect(reorder(SOURCES_ORDER, List.of(b, a, c), adminToken), 200);
        assertThat(sortOrders(saved)).containsExactly(0, 1, 2);
        assertThat(ids(expect(get(SOURCES, adminToken), 200))).containsExactlyElementsOf(strings(b, a, c));

        // Sending the order that is already saved changes no row, not even updatedAt.
        List<Map<String, Object>> before = catalogRows();
        clock.set(START.plusSeconds(60));
        assertThat(expect(reorder(SOURCES_ORDER, List.of(b, a, c), adminToken), 200)).isEqualTo(saved);
        assertThat(catalogRows()).isEqualTo(before);

        // A catalog type without values accepts an empty order.
        var empty = reorder(BASE + "EMPLOYMENT_TYPE/order", List.of(), adminToken);
        assertThat(expect(empty, 200).isArray()).isTrue();
        assertThat(expect(empty, 200).isEmpty()).isTrue();
    }

    @Test
    void rejectsAnOrderThatIsNotExactlyTheCurrentValuesOfTheCatalog() throws Exception {
        UUID first = item(SOURCES, "FIRST", "First", true);
        UUID second = item(SOURCES, "SECOND", "Second", false);
        UUID reason = item(REASONS, "REASON", "Reason", true);
        List<Map<String, Object>> before = catalogRows();

        Map<String, List<UUID>> wrongOrders = new LinkedHashMap<>();
        wrongOrders.put("a value is missing", List.of(second));
        wrongOrders.put("nothing is sent", List.of());
        wrongOrders.put("an unknown id is added", List.of(second, first, UUID.randomUUID()));
        wrongOrders.put("a value of another catalog type is added", List.of(second, first, reason));
        wrongOrders.put("a value is repeated instead of another", List.of(second, second));
        wrongOrders.put("a value is repeated", List.of(second, first, first));
        for (var wrong : wrongOrders.entrySet()) {
            var response = reorder(SOURCES_ORDER, wrong.getValue(), adminToken);
            JsonNode body = expect(response, 400);
            assertThat(body.path("code").asText()).as(wrong.getKey()).isEqualTo("RECRUITMENT_CATALOG_ORDER_MISMATCH");
            assertThat(body.path("message").asText()).as(wrong.getKey()).isEqualTo("Danh sách thứ tự phải gồm đúng "
                    + "mọi giá trị hiện có của loại danh mục, mỗi giá trị một lần. Hãy tải lại danh sách rồi sắp xếp lại.");
            noStore(response);
        }
        // A source sent with the rejection reasons is not part of that catalog either.
        error(reorder(BASE + "REJECTION_REASON/order", List.of(reason, first), adminToken),
                400, "RECRUITMENT_CATALOG_ORDER_MISMATCH");
        assertThat(catalogRows()).isEqualTo(before);
    }

    @Test
    void rejectsAnOrderBodyThatIsNotAListOfIds() throws Exception {
        UUID only = item(SOURCES, "ONLY", "Only", true);
        Map<String, Object> before = row(only);

        JsonNode missing = expect(request("PUT", SOURCES_ORDER, "{}", adminToken), 400);
        assertThat(missing.path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(missing.path("fieldErrors").path("itemIds").asText())
                .isEqualTo("Cần gửi danh sách giá trị theo thứ tự mới.");
        JsonNode nullId = expect(request("PUT", SOURCES_ORDER, "{\"itemIds\":[null]}", adminToken), 400);
        assertThat(nullId.path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(nullId.path("fieldErrors").path("itemIds[0]").asText())
                .isEqualTo("Mã định danh giá trị không được để trống.");
        // Ids are UUID strings inside an array, and the body has no other field.
        for (String body : List.of("{\"itemIds\":[\"not-a-uuid\"]}", "{\"itemIds\":\"" + only + "\"}",
                "{\"itemIds\":[\"" + only + "\"],\"sortOrder\":0}", "{\"itemIds\":[")) {
            assertThat(expect(request("PUT", SOURCES_ORDER, body, adminToken), 400).path("code").asText())
                    .as(body).isEqualTo("INVALID_JSON");
        }
        assertThat(row(only)).isEqualTo(before);
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void onlyAdminAndHrManagerCanReorderByDefault(Role role) throws Exception {
        account("role@example.test", Set.of(role));
        String token = login("role@example.test").path("accessToken").asText();
        UUID first = item(SOURCES, "FIRST", "First", true);
        UUID second = item(SOURCES, "SECOND", "Second", true);
        List<Map<String, Object>> before = catalogRows();

        var response = reorder(SOURCES_ORDER, List.of(second, first), token);
        if (role == Role.ADMIN || role == Role.HR_MANAGER) {
            assertThat(ids(expect(response, 200))).containsExactlyElementsOf(strings(second, first));
            assertThat(ids(expect(get(SOURCES, token), 200))).containsExactlyElementsOf(strings(second, first));
        } else {
            error(response, 403, "FORBIDDEN");
            assertThat(catalogRows()).isEqualTo(before);
            // The role can still read the catalog, in the old order.
            assertThat(ids(expect(get(SOURCES, token), 200))).containsExactlyElementsOf(strings(first, second));
        }
    }

    @Test
    void reorderRechecksThePermissionAfterWaitingForTheActorAccountLock() throws Exception {
        UUID first = item(SOURCES, "FIRST", "First", true);
        UUID second = item(SOURCES, "SECOND", "Second", true);
        List<Map<String, Object>> before = catalogRows();
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blockerPid = lockAccount(connection, adminId);
            try (var executor = Executors.newSingleThreadExecutor()) {
                // The request passed the URL rule already; the permission disappears while it waits.
                var response = executor.submit(() -> reorder(SOURCES_ORDER, List.of(second, first), adminToken));
                try {
                    awaitWaiters(blockerPid, 1);
                    jdbc.update("DELETE FROM role_permissions "
                            + "WHERE role_code = 'ADMIN' AND permission_code = 'ORGANIZATION_WRITE_ALL'");
                    connection.commit();
                    error(response.get(10, TimeUnit.SECONDS), 403, "FORBIDDEN");
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
        assertThat(catalogRows()).isEqualTo(before);
    }

    @Test
    void secondOfTwoReordersAtTheSameTimeWaitsAndThenSavesItsWholeOrder() throws Exception {
        // Two different writers, so the requests do not already wait for each other on the actor's account row.
        account("hr@example.test", Set.of(Role.HR_MANAGER));
        String hrToken = login("hr@example.test").path("accessToken").asText();
        UUID a = item(SOURCES, "A", "A", true);
        UUID b = item(SOURCES, "B", "B", true);
        UUID c = item(SOURCES, "C", "C", true);
        UUID d = item(SOURCES, "D", "D", true);
        List<UUID> firstOrder = List.of(d, c, b, a);
        // A and D keep their first numbers here. Had the second request used numbers read before the first one
        // committed, it would skip A and D and leave the first request's numbers on them: a mix of both orders.
        List<UUID> secondOrder = List.of(a, c, b, d);
        UUID firstLocked = jdbc.queryForObject(
                "SELECT id FROM recruitment_catalog_items WHERE catalog_type = 'CANDIDATE_SOURCE' ORDER BY id LIMIT 1",
                UUID.class);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            // The values are locked in id order, so holding the first one stops both requests before they lock any.
            int blockerPid = lockItem(connection, firstLocked);
            try (var executor = Executors.newFixedThreadPool(2)) {
                var first = executor.submit(() -> reorder(SOURCES_ORDER, firstOrder, adminToken));
                awaitWaiters(blockerPid, 1);
                var second = executor.submit(() -> reorder(SOURCES_ORDER, secondOrder, hrToken));
                try {
                    awaitWaiters(blockerPid, 2);
                    connection.commit();
                    // Both succeed (no deadlock) and each returns the whole order it saved.
                    assertThat(ids(expect(first.get(10, TimeUnit.SECONDS), 200)))
                            .containsExactlyElementsOf(strings(firstOrder));
                    assertThat(ids(expect(second.get(10, TimeUnit.SECONDS), 200)))
                            .containsExactlyElementsOf(strings(secondOrder));
                } finally {
                    connection.rollback();
                }
            }
        }
        // The second request ran after the first one committed, so its whole order is the one saved.
        JsonNode listed = expect(get(SOURCES, adminToken), 200);
        assertThat(ids(listed)).containsExactlyElementsOf(strings(secondOrder));
        assertThat(sortOrders(listed)).containsExactly(0, 1, 2, 3);
    }

    @Test
    void updateThatWaitsForAReorderKeepsTheNewlySavedOrder() throws Exception {
        UUID first = item(SOURCES, "FIRST", "First", true);
        UUID second = item(SOURCES, "SECOND", "Second", true);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            // Stands in for a reorder that has written new numbers but not committed yet.
            execute(connection, "UPDATE recruitment_catalog_items SET sort_order = 1 WHERE id = ?", first);
            execute(connection, "UPDATE recruitment_catalog_items SET sort_order = 0 WHERE id = ?", second);
            int blockerPid = backendPid(connection);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(() -> update(SOURCES, first, payload("FIRST", "Renamed", true),
                        adminToken));
                try {
                    awaitWaiters(blockerPid, 1);
                    connection.commit();
                    // The PUT reads the row only after the reorder commits, so it cannot write back the old number.
                    JsonNode updated = expect(response.get(10, TimeUnit.SECONDS), 200);
                    assertThat(updated.path("name").asText()).isEqualTo("Renamed");
                    assertThat(updated.path("sortOrder").asInt()).isEqualTo(1);
                } finally {
                    connection.rollback();
                }
            }
        }
        assertThat(ids(expect(get(SOURCES, adminToken), 200))).containsExactlyElementsOf(strings(second, first));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void valueDeletedWhileTheReorderWaitsMakesTheOrderOutOfDate(boolean deleteCommitted) throws Exception {
        UUID first = item(SOURCES, "FIRST", "First", true);
        UUID second = item(SOURCES, "SECOND", "Second", true);
        UUID third = item(SOURCES, "THIRD", "Third", true);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            // The uncommitted DELETE keeps its row locked, so the reorder has to wait for it.
            execute(connection, "DELETE FROM recruitment_catalog_items WHERE id = ?", second);
            int blockerPid = backendPid(connection);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(() -> reorder(SOURCES_ORDER, List.of(third, second, first), adminToken));
                try {
                    awaitWaiters(blockerPid, 1);
                    if (deleteCommitted) {
                        connection.commit();
                        error(response.get(10, TimeUnit.SECONDS), 400, "RECRUITMENT_CATALOG_ORDER_MISMATCH");
                    } else {
                        connection.rollback();
                        assertThat(ids(expect(response.get(10, TimeUnit.SECONDS), 200)))
                                .containsExactlyElementsOf(strings(third, second, first));
                    }
                } finally {
                    connection.rollback();
                }
            }
        }
        JsonNode listed = expect(get(SOURCES, adminToken), 200);
        if (deleteCommitted) {
            // Nothing was reordered: the two remaining values keep their old numbers.
            assertThat(ids(listed)).containsExactlyElementsOf(strings(first, third));
            assertThat(sortOrders(listed)).containsExactly(0, 2);
        } else {
            assertThat(ids(listed)).containsExactlyElementsOf(strings(third, second, first));
            assertThat(sortOrders(listed)).containsExactly(0, 1, 2);
        }
    }

    @ParameterizedTest(name = "{0} after {1}")
    @CsvSource({
            "update, lost-permission", "update, expired-jwt",
            "delete, lost-permission", "delete, expired-jwt",
            "reorder, lost-permission", "reorder, expired-jwt"})
    void rechecksAccessAfterWaitingForTheCatalogValueLock(String operation, String change) throws Exception {
        UUID target = item(SOURCES, "TARGET", "Target", true);
        UUID other = item(SOURCES, "OTHER", "Other", true);
        List<Map<String, Object>> before = catalogRows();
        try {
            // Another write holds the value: the request passes the account and session checks, then waits here.
            var response = sendWhileLocked(connection -> lockItem(connection, target),
                    () -> write(operation, target, other, adminToken), () -> {
                        if (change.equals("lost-permission")) {
                            revokeWriteGrant("ADMIN");
                        } else {
                            clock.set(START.plus(Duration.ofMinutes(15)));
                        }
                    });
            if (change.equals("lost-permission")) {
                error(response, 403, "FORBIDDEN");
            } else {
                error(response, 401, "SESSION_INVALID");
            }
        } finally {
            restoreDefaultWriteGrants();
        }
        assertThat(catalogRows()).isEqualTo(before);
    }

    @Test
    void updateRechecksThePermissionAfterWaitingForTheActorAccountLock() throws Exception {
        UUID target = item(SOURCES, "KEEP", "Keep", true);
        Map<String, Object> before = row(target);
        try {
            // The request passed the URL rule already; the permission disappears while it waits.
            var response = sendWhileLocked(connection -> lockAccount(connection, adminId),
                    () -> update(SOURCES, target, payload("KEEP", "Changed", false), adminToken),
                    () -> revokeWriteGrant("ADMIN"));
            error(response, 403, "FORBIDDEN");
        } finally {
            restoreDefaultWriteGrants();
        }
        assertThat(row(target)).isEqualTo(before);
    }

    @Test
    void managementFollowsTheWriteGrantInTheDatabaseNotTheRoleName() throws Exception {
        account("recruiter@example.test", Set.of(Role.RECRUITER));
        account("hr@example.test", Set.of(Role.HR_MANAGER));
        String recruiterToken = login("recruiter@example.test").path("accessToken").asText();
        String hrToken = login("hr@example.test").path("accessToken").asText();
        UUID kept = item(SOURCES, "KEEP", "Keep", true);
        try {
            error(create(SOURCES, payload("BY_RECRUITER", "By recruiter", true), recruiterToken), 403, "FORBIDDEN");

            // Granting the permission to RECRUITER opens every catalog write to recruiters, with the token they have.
            jdbc.update("INSERT INTO role_permissions (role_code, permission_code) "
                    + "VALUES ('RECRUITER', 'ORGANIZATION_WRITE_ALL')");
            UUID created = UUID.fromString(expect(create(SOURCES, payload("BY_RECRUITER", "By recruiter", true),
                    recruiterToken), 201).path("id").asText());
            assertThat(expect(update(SOURCES, created, payload("BY_RECRUITER", "Renamed", true), recruiterToken), 200)
                    .path("name").asText()).isEqualTo("Renamed");
            assertThat(ids(expect(reorder(SOURCES_ORDER, List.of(created, kept), recruiterToken), 200)))
                    .containsExactlyElementsOf(strings(created, kept));
            assertThat(delete(SOURCES, created, recruiterToken).statusCode()).isEqualTo(204);

            // Taking it away from HR_MANAGER closes every write to HR managers, again without a new login.
            revokeWriteGrant("HR_MANAGER");
            List<Map<String, Object>> before = catalogRows();
            error(create(SOURCES, payload("BY_HR", "By HR", true), hrToken), 403, "FORBIDDEN");
            error(update(SOURCES, kept, payload("KEEP", "By HR", false), hrToken), 403, "FORBIDDEN");
            error(reorder(SOURCES_ORDER, List.of(kept), hrToken), 403, "FORBIDDEN");
            error(delete(SOURCES, kept, hrToken), 403, "FORBIDDEN");
            assertThat(catalogRows()).isEqualTo(before);
            // Reading needs only ORGANIZATION_READ_ALL, which HR_MANAGER keeps.
            assertThat(ids(expect(get(SOURCES, hrToken), 200))).containsExactly(kept.toString());
        } finally {
            restoreDefaultWriteGrants();
        }
    }

    @Test
    void roleRemovedWhileACatalogWriteRunsWaitsForItAndAppliesFromTheNextRequest() throws Exception {
        UUID hrId = account("hr@example.test", Set.of(Role.HR_MANAGER, Role.RECRUITER));
        String hrToken = login("hr@example.test").path("accessToken").asText();
        UUID target = item(SOURCES, "TARGET", "Target", true);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            // The HR manager's update passes every check, keeps the HR account locked, then waits for this value.
            int blockerPid = lockItem(connection, target);
            try (var executor = Executors.newFixedThreadPool(2)) {
                var write = executor.submit(() -> update(SOURCES, target, payload("TARGET", "By HR", true), hrToken));
                awaitWaiters(blockerPid, 1);
                // The account role API locks the HR account too, so the role change queues behind the running write.
                var removal = executor.submit(() -> request("DELETE",
                        "/api/v1/accounts/" + hrId + "/roles/HR_MANAGER", null, adminToken));
                try {
                    awaitWaiters(blockerPid, 2);
                    connection.commit();
                    assertThat(expect(write.get(10, TimeUnit.SECONDS), 200).path("name").asText()).isEqualTo("By HR");
                    JsonNode roles = expect(removal.get(10, TimeUnit.SECONDS), 200).path("roles");
                    assertThat(roles.size()).isEqualTo(1);
                    assertThat(roles.get(0).asText()).isEqualTo("RECRUITER");
                } finally {
                    connection.rollback();
                }
            }
        }
        // Same token, next requests: as a recruiter the account still reads the catalog but cannot change it.
        Map<String, Object> before = row(target);
        error(update(SOURCES, target, payload("TARGET", "Too late", true), hrToken), 403, "FORBIDDEN");
        error(create(SOURCES, payload("NEW", "New", true), hrToken), 403, "FORBIDDEN");
        assertThat(row(target)).isEqualTo(before);
        assertThat(count()).isEqualTo(1);
        assertThat(expect(get(SOURCES + "/" + target, hrToken), 200).path("name").asText()).isEqualTo("By HR");
    }

    // One write of each kind; if it were allowed, it would change the target value.
    private HttpResponse<String> write(String operation, UUID target, UUID other, String token) throws Exception {
        return switch (operation) {
            case "update" -> update(SOURCES, target, payload("TARGET", "Changed", false), token);
            case "delete" -> delete(SOURCES, target, token);
            default -> reorder(SOURCES_ORDER, List.of(other, target), token);
        };
    }

    private void revokeWriteGrant(String role) {
        jdbc.update("DELETE FROM role_permissions WHERE role_code = ? AND permission_code = 'ORGANIZATION_WRITE_ALL'",
                role);
    }

    // Puts ORGANIZATION_WRITE_ALL back to the V3 grants (ADMIN and HR_MANAGER only) after a test changed them.
    private void restoreDefaultWriteGrants() {
        jdbc.update("DELETE FROM role_permissions WHERE permission_code = 'ORGANIZATION_WRITE_ALL' "
                + "AND role_code NOT IN ('ADMIN', 'HR_MANAGER')");
        jdbc.update("""
                INSERT INTO role_permissions (role_code, permission_code)
                VALUES ('ADMIN', 'ORGANIZATION_WRITE_ALL'), ('HR_MANAGER', 'ORGANIZATION_WRITE_ALL')
                ON CONFLICT DO NOTHING
                """);
    }

    // Another transaction takes a row lock, the request is sent and waits for it, whileWaiting runs, then the lock
    // is released and the request's response is returned.
    private HttpResponse<String> sendWhileLocked(RowLock lock, Callable<HttpResponse<String>> request,
                                                 Runnable whileWaiting) throws Exception {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blockerPid = lock.acquire(connection);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(request);
                try {
                    awaitWaiters(blockerPid, 1);
                    whileWaiting.run();
                    connection.commit();
                    return response.get(10, TimeUnit.SECONDS);
                } finally {
                    connection.rollback();
                }
            }
        }
    }

    // Locks one row in the given transaction and returns that transaction's backend pid.
    @FunctionalInterface
    private interface RowLock {
        int acquire(Connection connection) throws Exception;
    }

    // No real table stores catalog values yet, so these tests create a small referencing table of their own.
    private void createReferenceTable(String onDelete) {
        jdbc.execute("CREATE TABLE " + REFERENCES + " (id UUID PRIMARY KEY, catalog_item_id UUID NOT NULL "
                + "REFERENCES recruitment_catalog_items (id) ON DELETE " + onDelete + ")");
    }

    private int referencesTo(UUID itemId) {
        return jdbc.queryForObject("SELECT count(*) FROM " + REFERENCES + " WHERE catalog_item_id = ?",
                Integer.class, itemId);
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

    // Every catalog row of every type, so a check also notices changes outside the catalog type being tested.
    private List<Map<String, Object>> catalogRows() {
        return jdbc.queryForList("SELECT * FROM recruitment_catalog_items ORDER BY id");
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

    private HttpResponse<String> delete(String items, UUID id, String token) throws Exception {
        return request("DELETE", items + "/" + id, null, token);
    }

    private HttpResponse<String> reorder(String order, List<UUID> itemIds, String token) throws Exception {
        return request("PUT", order, json.writeValueAsString(Map.of("itemIds", itemIds)), token);
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

    private List<Integer> sortOrders(JsonNode array) {
        List<Integer> sortOrders = new ArrayList<>();
        array.forEach(item -> sortOrders.add(item.path("sortOrder").asInt()));
        return sortOrders;
    }

    private static List<String> strings(UUID... ids) {
        return strings(List.of(ids));
    }

    private static List<String> strings(List<UUID> ids) {
        return ids.stream().map(UUID::toString).toList();
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

    private int lockItem(Connection connection, UUID id) throws Exception {
        try (var statement = connection.prepareStatement(
                "SELECT id FROM recruitment_catalog_items WHERE id = ? FOR UPDATE")) {
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
