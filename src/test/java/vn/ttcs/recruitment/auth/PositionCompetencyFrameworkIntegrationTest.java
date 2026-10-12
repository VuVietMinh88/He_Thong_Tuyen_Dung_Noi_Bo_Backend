package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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

// Jira 214: many positions point to one competency framework (positions.competency_framework_id) and share its
// criteria rows instead of copying them.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PositionCompetencyFrameworkIntegrationTest {
    private static final String POSITIONS = "/api/v1/positions";
    private static final String FRAMEWORKS = "/api/v1/competency-frameworks";
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
    private String adminToken;
    // Only HR_MANAGER may create positions (salary band permission), so the HR manager writes the fixtures.
    private UUID hrId;
    private String hrToken;
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
        // Positions first: a framework still used by a position cannot be deleted (ON DELETE RESTRICT).
        jdbc.update("DELETE FROM positions");
        jdbc.update("DELETE FROM competency_frameworks");
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("UPDATE user_accounts SET admin_locked_at = NULL, admin_lock_reason = NULL, admin_locked_by = NULL");
        jdbc.update("DELETE FROM user_accounts");
        bootstrap.run(new DefaultApplicationArguments());
        JsonNode login = login("admin@example.test");
        adminToken = login.path("accessToken").asText();
        UUID adminId = UUID.fromString(login.path("user").path("id").asText());
        fixturePasswordHash = accounts.findById(adminId).orElseThrow().getPasswordHash();
        hrId = account("hr@example.test", Set.of(Role.HR_MANAGER));
        hrToken = login("hr@example.test").path("accessToken").asText();
    }

    @Test
    void twoPositionsShareOneFrameworkAndItsCriteriaAreStoredOnlyOnce() throws Exception {
        JsonNode framework = activeFramework("DEV_CORE", criterion(null, "Kỹ năng lập trình", 40),
                criterion(null, "Thiết kế hệ thống", 35), criterion(null, "Làm việc nhóm", 25));
        UUID frameworkId = id(framework);
        UUID senior = position("DEV_SENIOR", "Lập trình viên", "Senior");
        UUID junior = position("DEV_JUNIOR", "Lập trình viên", "Junior");
        var criteriaBefore = criteriaRows();
        assertThat(criteriaBefore).hasSize(3);

        clock.set(START.plusSeconds(30));
        for (UUID position : List.of(junior, senior)) {
            var response = assign(position, frameworkId, hrToken);
            JsonNode view = expect(response, 200);
            noStore(response);
            assertThat(view.path("id").asText()).isEqualTo(position.toString());
            assertThat(view.path("competencyFrameworkId").asText()).isEqualTo(frameworkId.toString());
            // The HR manager also sees the salary band, so every position field is there.
            assertThat(view.size()).isEqualTo(10);
            assertThat(Instant.parse(view.path("createdAt").asText())).isEqualTo(START);
            assertThat(Instant.parse(view.path("updatedAt").asText())).isEqualTo(START.plusSeconds(30));
            assertThat(expect(get(POSITIONS + "/" + position, hrToken), 200)).isEqualTo(view);
        }

        // Both rows point to the same framework id, and not a single criterion row was added or changed.
        assertThat(jdbc.queryForList("SELECT competency_framework_id FROM positions ORDER BY code", UUID.class))
                .containsExactly(frameworkId, frameworkId);
        assertThat(criteriaRows()).isEqualTo(criteriaBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM competency_frameworks", Integer.class)).isEqualTo(1);

        // The framework detail lists the positions that use it, ordered by code, without any salary.
        var detailResponse = get(FRAMEWORKS + "/" + frameworkId, hrToken);
        JsonNode detail = expect(detailResponse, 200);
        noStore(detailResponse);
        assertThat(detail.size()).isEqualTo(9);
        assertThat(ids(detail.path("criteria"))).containsExactlyElementsOf(ids(framework.path("criteria")));
        JsonNode positions = detail.path("positions");
        assertThat(ids(positions)).containsExactly(junior.toString(), senior.toString());
        assertThat(positions.path(0).size()).isEqualTo(5);
        assertThat(positions.path(0).path("code").asText()).isEqualTo("DEV_JUNIOR");
        assertThat(positions.path(0).path("name").asText()).isEqualTo("Lập trình viên");
        assertThat(positions.path(0).path("level").asText()).isEqualTo("Junior");
        assertThat(positions.path(0).path("active").asBoolean()).isTrue();
        assertThat(positions.path(1).path("level").asText()).isEqualTo("Senior");
        assertThat(detailResponse.body()).doesNotContain("salary");

        // One edit of the framework reaches both positions, because they read the same rows.
        List<String> criterionIds = ids(framework.path("criteria"));
        var edited = expect(request("PUT", FRAMEWORKS + "/" + frameworkId, json.writeValueAsString(payload("DEV_CORE",
                List.of(criterion(UUID.fromString(criterionIds.get(0)), "Lập trình", 50),
                        criterion(UUID.fromString(criterionIds.get(1)), "Thiết kế hệ thống", 25),
                        criterion(UUID.fromString(criterionIds.get(2)), "Làm việc nhóm", 25)), null)), hrToken), 200);
        assertThat(ids(edited.path("positions"))).containsExactly(junior.toString(), senior.toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM competency_criteria", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForList("""
                SELECT c.name FROM positions p JOIN competency_criteria c ON c.framework_id = p.competency_framework_id
                WHERE p.id = ? ORDER BY c.sort_order
                """, String.class, senior)).containsExactly("Lập trình", "Thiết kế hệ thống", "Làm việc nhóm");
        assertThat(jdbc.queryForList("""
                SELECT c.id FROM positions p JOIN competency_criteria c ON c.framework_id = p.competency_framework_id
                WHERE p.id = ? ORDER BY c.sort_order
                """, UUID.class, junior)).extracting(UUID::toString).containsExactlyElementsOf(criterionIds);
    }

    @Test
    void onlyACompleteFrameworkCanBeAssigned() throws Exception {
        // A DRAFT may already total 100%; it still is not marked complete, so it cannot be used yet.
        JsonNode draft = framework("DRAFT_CORE", null, criterion(null, "Giao tiếp", 60), criterion(null, "Tư duy", 40));
        UUID frameworkId = id(draft);
        UUID position = position("DEV", "Developer", "Junior");
        var before = positionRow(position);

        clock.set(START.plusSeconds(30));
        var refused = assign(position, frameworkId, hrToken);
        JsonNode body = expect(refused, 409);
        noStore(refused);
        assertThat(body.path("code").asText()).isEqualTo("COMPETENCY_FRAMEWORK_NOT_ACTIVE");
        assertThat(body.path("message").asText())
                .isEqualTo("Chỉ gán được khung năng lực đã hoàn chỉnh (ACTIVE) cho chức danh.");
        assertThat(body.path("fieldErrors").size()).isEqualTo(1);
        assertThat(body.path("fieldErrors").path("frameworkId").asText()).isNotBlank();
        assertThat(positionRow(position)).isEqualTo(before);
        assertThat(expect(get(FRAMEWORKS + "/" + frameworkId, hrToken), 200).path("positions").isEmpty()).isTrue();

        // Once HR marks the framework complete, the same request succeeds.
        List<String> criterionIds = ids(draft.path("criteria"));
        expect(request("PUT", FRAMEWORKS + "/" + frameworkId, json.writeValueAsString(payload("DRAFT_CORE",
                List.of(criterion(UUID.fromString(criterionIds.get(0)), "Giao tiếp", 60),
                        criterion(UUID.fromString(criterionIds.get(1)), "Tư duy", 40)), "ACTIVE")), hrToken), 200);
        assertThat(expect(assign(position, frameworkId, hrToken), 200).path("competencyFrameworkId").asText())
                .isEqualTo(frameworkId.toString());
    }

    @Test
    void aFrameworkInUseStaysCompleteSoItsPositionsNeverPointToAnIncompleteOne() throws Exception {
        JsonNode framework = activeFramework("CORE", criterion(null, "Giao tiếp", 50), criterion(null, "Tư duy", 50));
        UUID frameworkId = id(framework);
        UUID position = position("DEV", "Developer", "Junior");
        expect(assign(position, frameworkId, hrToken), 200);
        var frameworkBefore = jdbc.queryForMap("SELECT * FROM competency_frameworks WHERE id = ?", frameworkId);
        var criteriaBefore = criteriaRows();
        List<String> criterionIds = ids(framework.path("criteria"));

        // Back to DRAFT is refused, so the weights cannot become incomplete through a draft either.
        error(request("PUT", FRAMEWORKS + "/" + frameworkId, json.writeValueAsString(payload("CORE",
                List.of(criterion(UUID.fromString(criterionIds.get(0)), "Giao tiếp", 50)), "DRAFT")), hrToken),
                409, "COMPETENCY_FRAMEWORK_ALREADY_ACTIVE");
        // Dropping a criterion without re-balancing leaves 50%, which an ACTIVE framework never accepts.
        error(request("PUT", FRAMEWORKS + "/" + frameworkId, json.writeValueAsString(payload("CORE",
                List.of(criterion(UUID.fromString(criterionIds.get(0)), "Giao tiếp", 50)), null)), hrToken),
                400, "COMPETENCY_FRAMEWORK_WEIGHT_TOTAL_INVALID");

        assertThat(jdbc.queryForMap("SELECT * FROM competency_frameworks WHERE id = ?", frameworkId))
                .isEqualTo(frameworkBefore);
        assertThat(criteriaRows()).isEqualTo(criteriaBefore);
        assertThat(jdbc.queryForObject("SELECT competency_framework_id FROM positions WHERE id = ?", UUID.class,
                position)).isEqualTo(frameworkId);
    }

    @Test
    void switchingOrRemovingTheFrameworkOnlyChangesTheLinkOfThatPosition() throws Exception {
        UUID first = id(activeFramework("FIRST", criterion(null, "Giao tiếp", 100)));
        UUID second = id(activeFramework("SECOND", criterion(null, "Tư duy", 100)));
        UUID moved = position("DEV", "Developer", "Junior");
        UUID staying = position("QA", "Tester", "Junior");
        expect(assign(moved, first, hrToken), 200);
        expect(assign(staying, first, hrToken), 200);
        var criteriaBefore = criteriaRows();

        // Assigning the framework it already uses is accepted and keeps the link.
        assertThat(expect(assign(moved, first, hrToken), 200).path("competencyFrameworkId").asText())
                .isEqualTo(first.toString());

        clock.set(START.plusSeconds(30));
        JsonNode switched = expect(assign(moved, second, hrToken), 200);
        assertThat(switched.path("competencyFrameworkId").asText()).isEqualTo(second.toString());
        assertThat(ids(expect(get(FRAMEWORKS + "/" + first, hrToken), 200).path("positions")))
                .containsExactly(staying.toString());
        assertThat(ids(expect(get(FRAMEWORKS + "/" + second, hrToken), 200).path("positions")))
                .containsExactly(moved.toString());

        clock.set(START.plusSeconds(60));
        var removedResponse = unassign(moved, hrToken);
        JsonNode removed = expect(removedResponse, 200);
        noStore(removedResponse);
        assertThat(removed.has("competencyFrameworkId")).isTrue();
        assertThat(removed.path("competencyFrameworkId").isNull()).isTrue();
        assertThat(Instant.parse(removed.path("updatedAt").asText())).isEqualTo(START.plusSeconds(60));
        assertThat(expect(get(POSITIONS + "/" + moved, hrToken), 200)).isEqualTo(removed);
        assertThat(expect(get(FRAMEWORKS + "/" + second, hrToken), 200).path("positions").isEmpty()).isTrue();

        // Removing it again is harmless: the position simply stays without a framework.
        assertThat(expect(unassign(moved, hrToken), 200).path("competencyFrameworkId").isNull()).isTrue();

        // The frameworks, their criteria and the other position are untouched.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM competency_frameworks", Integer.class)).isEqualTo(2);
        assertThat(criteriaRows()).isEqualTo(criteriaBefore);
        assertThat(jdbc.queryForObject("SELECT competency_framework_id FROM positions WHERE id = ?", UUID.class,
                staying)).isEqualTo(first);
    }

    @Test
    void editingThePositionCatalogKeepsTheFrameworkLinkAndCannotChangeIt() throws Exception {
        UUID frameworkId = id(activeFramework("CORE", criterion(null, "Giao tiếp", 100)));
        UUID position = position("DEV", "Developer", "Junior");
        expect(assign(position, frameworkId, hrToken), 200);

        JsonNode updated = expect(request("PUT", POSITIONS + "/" + position,
                json.writeValueAsString(positionPayload("DEV", "Senior developer", "Senior")), hrToken), 200);
        assertThat(updated.path("name").asText()).isEqualTo("Senior developer");
        assertThat(updated.path("competencyFrameworkId").asText()).isEqualTo(frameworkId.toString());

        // The link is not a catalog field: sending it there is refused like any unknown field.
        Map<String, Object> withLink = positionPayload("DEV", "Changed", "Senior");
        withLink.put("competencyFrameworkId", null);
        var before = positionRow(position);
        error(request("PUT", POSITIONS + "/" + position, json.writeValueAsString(withLink), hrToken),
                400, "INVALID_JSON");
        assertThat(positionRow(position)).isEqualTo(before);
        assertThat(before.get("competency_framework_id")).isEqualTo(frameworkId);
    }

    @Test
    void validatesTheRequestAndReportsUnknownPositionsAndFrameworks() throws Exception {
        UUID frameworkId = id(activeFramework("CORE", criterion(null, "Giao tiếp", 100)));
        UUID position = position("DEV", "Developer", "Junior");
        String path = POSITIONS + "/" + position + "/competency-framework";
        var before = positionRow(position);

        // frameworkId is required; a separate DELETE removes the framework.
        for (String body : List.of("{}", "{\"frameworkId\":null}")) {
            JsonNode error = expect(request("PUT", path, body, hrToken), 400);
            assertThat(error.path("code").asText()).as(body).isEqualTo("VALIDATION_ERROR");
            assertThat(error.path("fieldErrors").size()).as(body).isEqualTo(1);
            assertThat(error.path("fieldErrors").path("frameworkId").asText()).isEqualTo("Cần chọn khung năng lực.");
        }
        for (String body : List.of("{\"frameworkId\":\"not-a-uuid\"}", "{\"frameworkId\":12}",
                "{\"frameworkId\":\"" + frameworkId + "\",\"active\":true}", "{\"frameworkId\":", "")) {
            error(request("PUT", path, body, hrToken), 400, "INVALID_JSON");
        }

        var unknownFramework = assign(position, UUID.randomUUID(), hrToken);
        JsonNode body = expect(unknownFramework, 400);
        noStore(unknownFramework);
        assertThat(body.path("code").asText()).isEqualTo("INVALID_COMPETENCY_FRAMEWORK");
        assertThat(body.path("message").asText()).isEqualTo("Khung năng lực không tồn tại.");
        assertThat(body.path("fieldErrors").path("frameworkId").asText()).isNotBlank();

        // The position in the URL is checked first, so an unknown position wins over an unknown framework.
        for (Object framework : List.of(frameworkId, UUID.randomUUID())) {
            var missing = assign(UUID.randomUUID(), framework, hrToken);
            error(missing, 404, "POSITION_NOT_FOUND");
            noStore(missing);
        }
        error(unassign(UUID.randomUUID(), hrToken), 404, "POSITION_NOT_FOUND");
        error(request("PUT", POSITIONS + "/not-a-uuid/competency-framework",
                json.writeValueAsString(Map.of("frameworkId", frameworkId)), hrToken), 400, "VALIDATION_ERROR");
        error(request("DELETE", POSITIONS + "/not-a-uuid/competency-framework", null, hrToken), 400,
                "VALIDATION_ERROR");
        // Only PUT and DELETE exist on this URL.
        error(request("GET", path, null, hrToken), 403, "FORBIDDEN");

        assertThat(positionRow(position)).isEqualTo(before);
        assertThat(before.get("competency_framework_id")).isNull();
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void everyInternalRoleSeesTheLinkButOnlyOrganizationWritersChangeIt(Role role) throws Exception {
        account("role@example.test", Set.of(role));
        String token = login("role@example.test").path("accessToken").asText();
        UUID frameworkId = id(activeFramework("CORE", criterion(null, "Giao tiếp", 100)));
        UUID linked = position("LINKED", "Linked", "Junior");
        UUID free = position("FREE", "Free", "Junior");
        expect(assign(linked, frameworkId, hrToken), 200);
        // V3 grants ORGANIZATION_WRITE_ALL to ADMIN and HR_MANAGER only; the salary permission is not needed here.
        boolean writer = role == Role.ADMIN || role == Role.HR_MANAGER;

        // Every internal role (interviewers too) reads which positions use the framework.
        assertThat(ids(expect(get(FRAMEWORKS + "/" + frameworkId, token), 200).path("positions")))
                .containsExactly(linked.toString());
        assertThat(expect(get(POSITIONS + "/" + linked, token), 200).path("competencyFrameworkId").asText())
                .isEqualTo(frameworkId.toString());

        var before = jdbc.queryForList("SELECT * FROM positions ORDER BY code");
        var assigned = assign(free, frameworkId, token);
        var removed = unassign(linked, token);
        // Without write permission the request is refused before the body is read: 403, not 400.
        var invalid = request("PUT", POSITIONS + "/" + free + "/competency-framework", "{}", token);
        if (writer) {
            JsonNode view = expect(assigned, 200);
            assertThat(view.path("competencyFrameworkId").asText()).isEqualTo(frameworkId.toString());
            // ADMIN changes the link but still never sees the salary band (Jira 205).
            assertThat(view.has("salaryMin")).isEqualTo(role == Role.HR_MANAGER);
            assertThat(view.size()).isEqualTo(role == Role.HR_MANAGER ? 10 : 8);
            assertThat(expect(removed, 200).path("competencyFrameworkId").isNull()).isTrue();
            error(invalid, 400, "VALIDATION_ERROR");
            assertThat(ids(expect(get(FRAMEWORKS + "/" + frameworkId, token), 200).path("positions")))
                    .containsExactly(free.toString());
        } else {
            error(assigned, 403, "FORBIDDEN");
            error(removed, 403, "FORBIDDEN");
            error(invalid, 403, "FORBIDDEN");
            assertThat(jdbc.queryForList("SELECT * FROM positions ORDER BY code")).isEqualTo(before);
        }
    }

    @Test
    void anAssignmentThatWaitsForAnActivationInProgressUsesTheCommittedStatus() throws Exception {
        // Complete weights, but still a DRAFT until the other transaction below commits its activation.
        UUID frameworkId = id(framework("CORE", null, criterion(null, "Giao tiếp", 100)));
        UUID position = position("DEV", "Developer", "Junior");
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            // Plays a framework edit that holds the framework lock and activates it.
            lockRow(connection, "SELECT id FROM competency_frameworks WHERE id = ? FOR UPDATE", frameworkId);
            execute(connection, "UPDATE competency_frameworks SET status = 'ACTIVE' WHERE id = ?", frameworkId);
            int blockerPid = backendPid(connection);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(() -> assign(position, frameworkId, hrToken));
                try {
                    awaitWaiters(blockerPid);
                    assertThat(response.isDone()).isFalse();
                    connection.commit();
                    // FOR SHARE waited for the edit, so it reads ACTIVE instead of the DRAFT it would have seen.
                    assertThat(expect(response.get(10, TimeUnit.SECONDS), 200).path("competencyFrameworkId").asText())
                            .isEqualTo(frameworkId.toString());
                } finally {
                    connection.rollback();
                }
            }
        }
        assertThat(jdbc.queryForObject("SELECT competency_framework_id FROM positions WHERE id = ?", UUID.class,
                position)).isEqualTo(frameworkId);
    }

    @Test
    void assignmentsOfTheSameFrameworkDoNotWaitForEachOther() throws Exception {
        UUID frameworkId = id(activeFramework("CORE", criterion(null, "Giao tiếp", 100)));
        UUID position = position("DEV", "Developer", "Junior");
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            // Plays another assignment of the same framework that has not committed yet.
            lockRow(connection, "SELECT id FROM competency_frameworks WHERE id = ? FOR SHARE", frameworkId);
            try {
                // Shared locks are compatible, so this request finishes while the other one is still open.
                assertThat(expect(assign(position, frameworkId, hrToken), 200).path("competencyFrameworkId").asText())
                        .isEqualTo(frameworkId.toString());
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void aCatalogEditThatWaitsForTheRowLockKeepsALinkCommittedMeanwhile() throws Exception {
        UUID frameworkId = id(activeFramework("CORE", criterion(null, "Giao tiếp", 100)));
        UUID position = position("DEV", "Developer", "Junior");
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            // Plays an assignment of the framework that holds the position lock and has not committed yet.
            lockRow(connection, "SELECT id FROM positions WHERE id = ? FOR UPDATE", position);
            execute(connection, "UPDATE positions SET competency_framework_id = ? WHERE id = ?", frameworkId, position);
            int blockerPid = backendPid(connection);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(() -> request("PUT", POSITIONS + "/" + position,
                        json.writeValueAsString(positionPayload("DEV", "Renamed", "Junior")), hrToken));
                try {
                    awaitWaiters(blockerPid);
                    assertThat(response.isDone()).isFalse();
                    connection.commit();
                    // The catalog edit reads the row after the lock, so writing it back keeps the new link.
                    JsonNode updated = expect(response.get(10, TimeUnit.SECONDS), 200);
                    assertThat(updated.path("name").asText()).isEqualTo("Renamed");
                    assertThat(updated.path("competencyFrameworkId").asText()).isEqualTo(frameworkId.toString());
                } finally {
                    connection.rollback();
                }
            }
        }
        assertThat(jdbc.queryForMap("SELECT name, competency_framework_id FROM positions WHERE id = ?", position))
                .isEqualTo(Map.of("name", "Renamed", "competency_framework_id", frameworkId));
    }

    @Test
    void rechecksThePermissionAfterWaitingForTheActorAccountLock() throws Exception {
        UUID frameworkId = id(activeFramework("CORE", criterion(null, "Giao tiếp", 100)));
        UUID position = position("DEV", "Developer", "Junior");
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            lockRow(connection, "SELECT id FROM user_accounts WHERE id = ? FOR UPDATE", hrId);
            int blockerPid = backendPid(connection);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(() -> assign(position, frameworkId, hrToken));
                try {
                    awaitWaiters(blockerPid);
                    // The HR manager loses the organization write permission while the request waits.
                    jdbc.update("DELETE FROM role_permissions "
                            + "WHERE role_code = 'HR_MANAGER' AND permission_code = 'ORGANIZATION_WRITE_ALL'");
                    connection.commit();
                    error(response.get(10, TimeUnit.SECONDS), 403, "FORBIDDEN");
                } finally {
                    connection.rollback();
                }
            }
        } finally {
            jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('HR_MANAGER', "
                    + "'ORGANIZATION_WRITE_ALL') ON CONFLICT DO NOTHING");
        }
        assertThat(jdbc.queryForObject("SELECT competency_framework_id FROM positions WHERE id = ?", UUID.class,
                position)).isNull();
    }

    private UUID account(String email, Set<Role> roles) {
        return accounts.saveAndFlush(new Account(email, "Framework link test", fixturePasswordHash, roles, START))
                .getId();
    }

    @SafeVarargs
    private JsonNode activeFramework(String code, Map<String, Object>... criteria) throws Exception {
        return framework(code, "ACTIVE", criteria);
    }

    // status null leaves the field out, so the new framework is a DRAFT.
    @SafeVarargs
    private JsonNode framework(String code, String status, Map<String, Object>... criteria) throws Exception {
        JsonNode created = expect(request("POST", FRAMEWORKS,
                json.writeValueAsString(payload(code, List.of(criteria), status)), hrToken), 201);
        assertThat(created.path("status").asText()).isEqualTo(status == null ? "DRAFT" : status);
        return created;
    }

    private static Map<String, Object> payload(String code, List<Map<String, Object>> criteria, String status) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", code);
        result.put("name", "Khung " + code);
        if (status != null) {
            result.put("status", status);
        }
        result.put("criteria", criteria);
        return result;
    }

    // The id is left out for a new criterion.
    private static Map<String, Object> criterion(UUID id, String name, Object weight) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (id != null) {
            result.put("id", id);
        }
        result.put("name", name);
        result.put("weight", weight);
        return result;
    }

    private UUID position(String code, String name, String level) throws Exception {
        return id(expect(request("POST", POSITIONS, json.writeValueAsString(positionPayload(code, name, level)),
                hrToken), 201));
    }

    private static Map<String, Object> positionPayload(String code, String name, String level) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", code);
        result.put("name", name);
        result.put("level", level);
        result.put("salaryMin", 15_000_000L);
        result.put("salaryMax", 25_000_000L);
        result.put("active", true);
        return result;
    }

    private HttpResponse<String> assign(UUID position, Object frameworkId, String token) throws Exception {
        return request("PUT", POSITIONS + "/" + position + "/competency-framework",
                json.writeValueAsString(Map.of("frameworkId", frameworkId)), token);
    }

    private HttpResponse<String> unassign(UUID position, String token) throws Exception {
        return request("DELETE", POSITIONS + "/" + position + "/competency-framework", null, token);
    }

    private Map<String, Object> positionRow(UUID id) {
        return jdbc.queryForMap("SELECT * FROM positions WHERE id = ?", id);
    }

    private List<Map<String, Object>> criteriaRows() {
        return jdbc.queryForList("SELECT * FROM competency_criteria ORDER BY id");
    }

    private JsonNode login(String email) throws Exception {
        return expect(request("POST", "/api/v1/auth/login",
                json.writeValueAsString(Map.of("email", email, "password", PASSWORD)), null), 200);
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

    private static UUID id(JsonNode node) { return UUID.fromString(node.path("id").asText()); }

    private List<String> ids(JsonNode array) {
        List<String> ids = new ArrayList<>();
        array.forEach(item -> ids.add(item.path("id").asText()));
        return ids;
    }

    private void lockRow(Connection connection, String sql, UUID id) throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (var row = statement.executeQuery()) { assertThat(row.next()).isTrue(); }
        }
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

    // Polls from a separate connection until an HTTP request waits for a lock held by the blocker.
    private void awaitWaiters(int blockerPid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        boolean waiting = false;
        while (!waiting && System.nanoTime() < deadline) {
            waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1 FROM pg_stat_activity
                        WHERE ? = ANY(pg_blocking_pids(pid)) AND wait_event_type = 'Lock'
                          AND datname = current_database()
                    )
                    """, Boolean.class, blockerPid));
            if (!waiting) {
                Thread.sleep(20);
            }
        }
        assertThat(waiting).as("the HTTP request must wait for the blocker's lock").isTrue();
    }
}
