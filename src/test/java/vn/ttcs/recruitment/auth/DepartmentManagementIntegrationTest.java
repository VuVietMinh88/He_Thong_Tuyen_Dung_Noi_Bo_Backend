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
import java.net.URLEncoder;
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
class DepartmentManagementIntegrationTest {
    private static final String BASE = "/api/v1/departments";
    private static final String PASSWORD = "TestingOnly123!";
    private static final Instant START = Instant.parse("2026-10-06T00:00:00Z");
    private static final long TREE_LOCK = 195196;

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
    private UUID managerId;

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
        jdbc.update("UPDATE departments SET parent_id = NULL");
        jdbc.update("DELETE FROM departments");
        jdbc.update("DELETE FROM user_accounts");
        bootstrap.run(new DefaultApplicationArguments());
        JsonNode login = login("admin@example.test");
        adminId = UUID.fromString(login.path("user").path("id").asText());
        adminToken = login.path("accessToken").asText();
        fixturePasswordHash = accounts.findById(adminId).orElseThrow().getPasswordHash();
        managerId = account("manager@example.test", Set.of(Role.INTERVIEWER));
    }

    @Test
    void createsTrimmedDepartmentAndReturnsOnlyPublicFieldsWithoutChangingManagerAccount() throws Exception {
        assertThat(expect(get(BASE + "/tree", adminToken), 200).isEmpty()).isTrue();
        JsonNode emptyPage = expect(get(BASE, adminToken), 200);
        assertThat(emptyPage.path("size").asInt()).isEqualTo(20);
        assertThat(emptyPage.path("totalElements").asInt()).isZero();
        Map<String, Object> managerBefore = jdbc.queryForMap("SELECT * FROM user_accounts WHERE id = ?", managerId);
        var response = create(payload("  HR  ", "  Nhân sự  ", null, managerId, true), adminToken);
        JsonNode result = expect(response, 201);
        UUID id = UUID.fromString(result.path("id").asText());
        assertThat(result.size()).isEqualTo(8);
        assertThat(result.path("code").asText()).isEqualTo("HR");
        assertThat(result.path("name").asText()).isEqualTo("Nhân sự");
        assertThat(result.path("parentId").isNull()).isTrue();
        assertThat(result.path("managerUserId").asText()).isEqualTo(managerId.toString());
        assertThat(result.path("managerFullName").asText()).isEqualTo("Department test");
        assertThat(result.path("active").asBoolean()).isTrue();
        assertThat(Instant.parse(result.path("createdAt").asText())).isEqualTo(START);
        noStore(response);
        assertThat(expect(get(BASE + "/" + id, adminToken), 200)).isEqualTo(result);
        assertThat(jdbc.queryForMap("SELECT * FROM user_accounts WHERE id = ?", managerId)).isEqualTo(managerBefore);
    }

    @Test
    void updatesAllEditableFieldsAndOmittedParentMovesDepartmentToRoot() throws Exception {
        UUID parent = department("PARENT", "Parent", null, managerId, true);
        UUID target = department("CHILD", "Child", parent, managerId, true);
        UUID replacement = account("replacement@example.test", Set.of(Role.RECRUITER));
        Instant created = Instant.parse(expect(get(BASE + "/" + target, adminToken), 200).path("createdAt").asText());
        Map<String, Object> body = payload("RENAMED", "Renamed child", null, replacement, false);
        body.remove("parentId");
        clock.set(START.plusSeconds(1));
        var response = update(target, body, adminToken);
        JsonNode result = expect(response, 200);
        assertThat(result.path("id").asText()).isEqualTo(target.toString());
        assertThat(result.path("code").asText()).isEqualTo("RENAMED");
        assertThat(result.path("name").asText()).isEqualTo("Renamed child");
        assertThat(result.path("parentId").isNull()).isTrue();
        assertThat(result.path("managerUserId").asText()).isEqualTo(replacement.toString());
        assertThat(result.path("active").asBoolean()).isFalse();
        assertThat(Instant.parse(result.path("createdAt").asText())).isEqualTo(created);
        noStore(response);
    }

    @Test
    void treeContainsEveryLevelAndDeactivatingParentDoesNotCascadeToChildrenOrEmployees() throws Exception {
        UUID root = department("ROOT", "Root", null, managerId, true);
        UUID child = department("CHILD", "Child", root, managerId, true);
        UUID leaf = department("LEAF", "Leaf", child, managerId, true);
        UUID separate = department("OTHER", "Other root", null, managerId, true);
        UUID employee = account("employee@example.test", Set.of(Role.RECRUITER));
        jdbc.update("UPDATE user_accounts SET department_id = ? WHERE id = ?", root, employee);
        expect(update(root, payload("ROOT", "Root", null, managerId, false), adminToken), 200);

        var response = get(BASE + "/tree", adminToken);
        JsonNode tree = expect(response, 200);
        noStore(response);
        assertThat(tree.isArray()).isTrue();
        assertThat(ids(tree)).containsExactlyInAnyOrder(root.toString(), separate.toString());
        JsonNode rootNode = find(tree, root);
        assertThat(rootNode.size()).isEqualTo(9);
        assertThat(rootNode.path("active").asBoolean()).isFalse();
        assertThat(ids(rootNode.path("children"))).containsExactly(child.toString());
        JsonNode childNode = rootNode.path("children").get(0);
        assertThat(childNode.path("active").asBoolean()).isTrue();
        assertThat(ids(childNode.path("children"))).containsExactly(leaf.toString());
        assertThat(childNode.path("children").get(0).path("children").isEmpty()).isTrue();
        assertThat(jdbc.queryForObject("SELECT department_id FROM user_accounts WHERE id = ?", UUID.class, employee)).isEqualTo(root);
        assertThat(jdbc.queryForObject("SELECT department_id FROM user_accounts WHERE id = ?", UUID.class, managerId)).isNull();
    }

    @Test
    void searchesCodeAndNameCaseInsensitivelyTreatingSqlWildcardsLiterally() throws Exception {
        UUID exact = department("PCT_%!", "Unique wording", null, managerId, true);
        department("NORMAL", "Other name", null, managerId, true);
        for (String query : List.of("pct_%!", "UNIQUE WORD", "%", "_", "!")) {
            JsonNode page = expect(get(BASE + "?q=" + encode(query), adminToken), 200);
            assertThat(page.path("totalElements").asInt()).isEqualTo(1);
            assertThat(ids(page.path("items"))).containsExactly(exact.toString());
        }
        assertThat(expect(get(BASE + "?q=" + encode("' OR 1=1 --"), adminToken), 200).path("totalElements").asInt()).isZero();
    }

    @Test
    void activeFilterAndPaginationHaveStableDisjointPagesAndEmptyPages() throws Exception {
        List<String> expected = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            expected.add(department("D" + index, "Department " + index, null, managerId, index % 2 == 0).toString());
        }
        List<String> seen = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            var response = get(BASE + "?page=" + page + "&size=2", adminToken);
            JsonNode result = expect(response, 200);
            noStore(response);
            assertThat(result.size()).isEqualTo(5);
            assertThat(result.path("page").asInt()).isEqualTo(page);
            assertThat(result.path("size").asInt()).isEqualTo(2);
            assertThat(result.path("totalElements").asInt()).isEqualTo(5);
            assertThat(result.path("totalPages").asInt()).isEqualTo(3);
            assertThat(result).isEqualTo(expect(get(BASE + "?page=" + page + "&size=2", adminToken), 200));
            seen.addAll(ids(result.path("items")));
        }
        assertThat(seen).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(expected);
        assertThat(expect(get(BASE + "?active=true", adminToken), 200).path("totalElements").asInt()).isEqualTo(3);
        assertThat(expect(get(BASE + "?active=false", adminToken), 200).path("totalElements").asInt()).isEqualTo(2);
        assertThat(expect(get(BASE + "?page=10&size=2", adminToken), 200).path("items").isEmpty()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void allInternalRolesCanReadButOnlyAdminAndHrManagerCanWriteByDefault(Role role) throws Exception {
        account("role@example.test", Set.of(role));
        String token = login("role@example.test").path("accessToken").asText();
        UUID target = department("READ", "Read department", null, managerId, true);
        for (String path : List.of(BASE, BASE + "/tree", BASE + "/" + target)) {
            expect(get(path, token), 200);
        }
        boolean writer = role == Role.ADMIN || role == Role.HR_MANAGER;
        assertThat(create(payload("NEW", "New department", null, managerId, true), token).statusCode()).isEqualTo(writer ? 201 : 403);
        assertThat(update(target, payload("READ", "Updated", null, managerId, true), token).statusCode()).isEqualTo(writer ? 200 : 403);
    }

    @Test
    void currentPermissionRemovalTakesEffectImmediatelyForAnExistingAccessToken() throws Exception {
        UUID target = department("RIGHTS", "Rights", null, managerId, true);
        jdbc.update("DELETE FROM role_permissions WHERE role_code = 'ADMIN' AND permission_code IN ('ORGANIZATION_READ_ALL', 'ORGANIZATION_WRITE_ALL')");
        try {
            assertThat(get(BASE, adminToken).statusCode()).isEqualTo(403);
            assertThat(get(BASE + "/tree", adminToken).statusCode()).isEqualTo(403);
            assertThat(get(BASE + "/" + target, adminToken).statusCode()).isEqualTo(403);
            assertThat(create(payload("NO", "No", null, managerId, true), adminToken).statusCode()).isEqualTo(403);
            assertThat(update(target, payload("RIGHTS", "No", null, managerId, true), adminToken).statusCode()).isEqualTo(403);
        } finally {
            restoreAdminPermissions();
        }
    }

    @Test
    void anonymousRevokedAndAdministrativelyLockedActorsAreRejected() throws Exception {
        UUID target = department("AUTH", "Authentication", null, managerId, true);
        assertThat(get(BASE, null).statusCode()).isEqualTo(401);
        assertThat(create(payload("NO", "No", null, managerId, true), null).statusCode()).isEqualTo(401);
        // Task 197 added DELETE; DepartmentDeletionIntegrationTest covers it in detail.
        assertThat(request("DELETE", BASE + "/" + target, null, null).statusCode()).isEqualTo(401);
        jdbc.update("UPDATE auth_sessions SET revoked_at = ? WHERE user_id = ?", Timestamp.from(START), adminId);
        assertThat(get(BASE, adminToken).statusCode()).isEqualTo(401);
        adminToken = login("admin@example.test").path("accessToken").asText();
        jdbc.update("UPDATE user_accounts SET admin_locked_at = ?, admin_lock_reason = 'Review', admin_locked_by = ? WHERE id = ?",
                Timestamp.from(START), managerId, adminId);
        assertThat(get(BASE, adminToken).statusCode()).isEqualTo(401);
        assertThat(update(target, payload("AUTH", "No", null, managerId, true), adminToken).statusCode()).isEqualTo(401);
        assertThat(request("DELETE", BASE + "/" + target, null, adminToken).statusCode()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM departments WHERE id = ?", Integer.class, target)).isEqualTo(1);
    }

    @Test
    void rejectsMissingBlankOversizedAndUnknownRequestFieldsButAcceptsLengthBoundaries() throws Exception {
        Map<String, Object> valid = payload("VALID", "Valid", null, managerId, true);
        for (String field : List.of("code", "name", "managerUserId", "active")) {
            Map<String, Object> missing = new LinkedHashMap<>(valid);
            missing.remove(field);
            error(create(missing, adminToken), 400, "VALIDATION_ERROR");
        }
        for (String field : List.of("code", "name")) {
            Map<String, Object> blank = new LinkedHashMap<>(valid);
            blank.put(field, " \t ");
            error(create(blank, adminToken), 400, "VALIDATION_ERROR");
            blank.put(field, "x".repeat(field.equals("code") ? 51 : 256));
            error(create(blank, adminToken), 400, "VALIDATION_ERROR");
        }
        Map<String, Object> unknown = new LinkedHashMap<>(valid);
        unknown.put("password", "ShouldNotBeAccepted");
        error(create(unknown, adminToken), 400, "INVALID_JSON");
        expect(create(payload("c".repeat(50), "n".repeat(255), null, managerId, true), adminToken), 201);
    }

    @Test
    void validatesListArgumentsAndReturnsConsistentMissingAndMalformedIdentifierErrors() throws Exception {
        for (String query : List.of("page=-1", "size=0", "size=101", "active=unknown", "page=abc", "q=" + "x".repeat(256))) {
            error(get(BASE + "?" + query, adminToken), 400, "VALIDATION_ERROR");
        }
        error(get(BASE + "/" + UUID.randomUUID(), adminToken), 404, "DEPARTMENT_NOT_FOUND");
        error(update(UUID.randomUUID(), payload("NONE", "None", null, managerId, true), adminToken), 404, "DEPARTMENT_NOT_FOUND");
        error(get(BASE + "/not-a-uuid", adminToken), 400, "VALIDATION_ERROR");
        Map<String, Object> malformed = payload("BAD", "Bad", null, managerId, true);
        malformed.put("managerUserId", "not-a-uuid");
        error(create(malformed, adminToken), 400, "INVALID_JSON");
    }

    @Test
    void codesAreCaseSensitiveUniqueAndDuplicateUpdateLeavesOriginalRowIntact() throws Exception {
        department("HR", "HR", null, managerId, true);
        UUID second = department("hr", "Lowercase", null, managerId, true);
        error(create(payload("HR", "Duplicate", null, managerId, true), adminToken), 409, "DEPARTMENT_CODE_EXISTS");
        Map<String, Object> before = jdbc.queryForMap("SELECT * FROM departments WHERE id = ?", second);
        error(update(second, payload("HR", "Duplicate update", null, managerId, true), adminToken), 409, "DEPARTMENT_CODE_EXISTS");
        assertThat(jdbc.queryForMap("SELECT * FROM departments WHERE id = ?", second)).isEqualTo(before);
    }

    @Test
    void supportsInactiveParentsButRejectsMissingParentSelfParentAndDescendantCycles() throws Exception {
        UUID root = department("ROOT", "Root", null, managerId, false);
        UUID child = department("CHILD", "Child", root, managerId, true);
        UUID leaf = department("LEAF", "Leaf", child, managerId, true);
        error(create(payload("MISSING", "Missing parent", UUID.randomUUID(), managerId, true), adminToken), 400, "INVALID_DEPARTMENT_PARENT");
        error(update(root, payload("ROOT", "Root", root, managerId, false), adminToken), 409, "DEPARTMENT_CYCLE");
        error(update(root, payload("ROOT", "Root", leaf, managerId, false), adminToken), 409, "DEPARTMENT_CYCLE");
        assertThat(jdbc.queryForObject("SELECT parent_id FROM departments WHERE id = ?", UUID.class, root)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "pending", "disabled", "locked"})
    void managerMustExistAndAllowAccessWhenCreatingChangingOrReactivating(String state) throws Exception {
        UUID invalid = UUID.randomUUID();
        if (state.equals("pending")) {
            invalid = accounts.saveAndFlush(Account.pendingActivation("pendingmanager@example.test", "Pending manager",
                    fixturePasswordHash, Set.of(Role.INTERVIEWER), START)).getId();
        } else if (!state.equals("missing")) {
            invalid = account("invalidmanager@example.test", Set.of(Role.INTERVIEWER));
            if (state.equals("disabled")) {
                jdbc.update("UPDATE user_accounts SET enabled = FALSE WHERE id = ?", invalid);
            } else {
                jdbc.update("UPDATE user_accounts SET admin_locked_at = ?, admin_lock_reason = 'Review', admin_locked_by = ? WHERE id = ?",
                        Timestamp.from(START), adminId, invalid);
            }
        }
        error(create(payload("BAD", "Bad manager", null, invalid, true), adminToken), 400, "INVALID_DEPARTMENT_MANAGER");
        UUID existing = department("EXIST", "Existing", null, managerId, true);
        error(update(existing, payload("EXIST", "Existing", null, invalid, true), adminToken), 400, "INVALID_DEPARTMENT_MANAGER");
        if (!state.equals("missing")) {
            UUID inactive = rawDepartment("INACTIVE", invalid, false);
            error(update(inactive, payload("INACTIVE", "Reactivate", null, invalid, true), adminToken), 400, "INVALID_DEPARTMENT_MANAGER");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"pending", "locked"})
    void unchangedUnavailableManagerDoesNotPreventOtherDepartmentEdits(String state) throws Exception {
        UUID department = department("EDIT", "Edit", null, managerId, true);
        if (state.equals("pending")) {
            jdbc.update("UPDATE user_accounts SET enabled = FALSE WHERE id = ?", managerId);
        } else {
            jdbc.update("UPDATE user_accounts SET admin_locked_at = ?, admin_lock_reason = 'Review', admin_locked_by = ? WHERE id = ?",
                    Timestamp.from(START), adminId, managerId);
        }
        expect(update(department, payload("EDIT", "Renamed", null, managerId, true), adminToken), 200);
        expect(update(department, payload("EDIT", "Deactivated", null, managerId, false), adminToken), 200);
        error(update(department, payload("EDIT", "Reactivate", null, managerId, true), adminToken), 400, "INVALID_DEPARTMENT_MANAGER");
    }

    @Test
    void malformedStoredTreeReturnsConflictInsteadOfSilentlyOmittingCyclicDepartments() throws Exception {
        UUID first = department("ONE", "One", null, managerId, true);
        UUID second = department("TWO", "Two", first, managerId, true);
        jdbc.update("UPDATE departments SET parent_id = ? WHERE id = ?", second, first);
        error(get(BASE + "/tree", adminToken), 409, "DEPARTMENT_TREE_INVALID");
    }

    @ParameterizedTest
    @ValueSource(strings = {"lost-permission", "expired-jwt"})
    void rechecksPermissionsAndExpiryAfterWaitingForTreeAdvisoryLock(String change) throws Exception {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blockerPid = lockTree(connection);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(() -> create(payload("WAIT", "Wait", null, managerId, true), adminToken));
                try {
                    awaitWaiters(blockerPid, 1);
                    if (change.equals("lost-permission")) {
                        jdbc.update("DELETE FROM role_permissions WHERE role_code = 'ADMIN' AND permission_code = 'ORGANIZATION_WRITE_ALL'");
                    } else {
                        clock.set(START.plus(Duration.ofMinutes(15)));
                    }
                    connection.commit();
                    assertThat(response.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(change.equals("lost-permission") ? 403 : 401);
                } finally {
                    connection.rollback();
                }
            }
        } finally {
            restoreAdminPermissions();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM departments", Integer.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"locked-actor", "revoked-session"})
    void rejectsActorInvalidationWhileRequestWaitsForItsAccountLock(String change) throws Exception {
        // This third user is outside the request's actor/manager pair, avoiding FK lock interference.
        UUID lockOwner = account("lockowner@example.test", Set.of(Role.ADMIN));
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blockerPid = lockRow(connection, "user_accounts", adminId);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(() -> create(payload("DENIED", "Denied", null, managerId, true), adminToken));
                try {
                    awaitWaiters(blockerPid, 1);
                    if (change.equals("locked-actor")) {
                        execute(connection, "UPDATE user_accounts SET admin_locked_at = ?, admin_lock_reason = 'Review', admin_locked_by = ? WHERE id = ?",
                                Timestamp.from(START), lockOwner, adminId);
                    } else {
                        execute(connection, "UPDATE auth_sessions SET revoked_at = ? WHERE user_id = ?", Timestamp.from(START), adminId);
                    }
                    connection.commit();
                    assertThat(response.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(401);
                } finally {
                    connection.rollback();
                }
            }
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM departments", Integer.class)).isZero();
    }

    @Test
    void simultaneousOppositeParentChangesCannotCreateACycle() throws Exception {
        account("secondadmin@example.test", Set.of(Role.ADMIN));
        String secondToken = login("secondadmin@example.test").path("accessToken").asText();
        UUID secondManager = account("secondmanager@example.test", Set.of(Role.INTERVIEWER));
        UUID first = department("ONE", "One", null, managerId, true);
        UUID second = department("TWO", "Two", null, secondManager, true);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blockerPid = lockTree(connection);
            try (var executor = Executors.newFixedThreadPool(2)) {
                var firstChange = executor.submit(() -> update(first, payload("ONE", "One", second, managerId, true), adminToken));
                var secondChange = executor.submit(() -> update(second, payload("TWO", "Two", first, secondManager, true), secondToken));
                try {
                    awaitWaiters(blockerPid, 2);
                    connection.commit();
                    var responses = List.of(firstChange.get(10, TimeUnit.SECONDS), secondChange.get(10, TimeUnit.SECONDS));
                    assertThat(responses.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(200, 409);
                    responses.stream().filter(response -> response.statusCode() == 409)
                            .forEach(response -> assertThat(body(response).path("code").asText()).isEqualTo("DEPARTMENT_CYCLE"));
                } finally {
                    connection.rollback();
                }
            }
        }
        JsonNode tree = expect(get(BASE + "/tree", adminToken), 200);
        assertThat(tree.size()).isEqualTo(1);
        assertThat(tree.get(0).path("children").size()).isEqualTo(1);
    }

    @Test
    void simultaneousDuplicateCodesHaveOneCreatedRowAndOneConflict() throws Exception {
        account("secondadmin@example.test", Set.of(Role.ADMIN));
        String secondToken = login("secondadmin@example.test").path("accessToken").asText();
        UUID secondManager = account("secondmanager@example.test", Set.of(Role.INTERVIEWER));
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blockerPid = lockTree(connection);
            try (var executor = Executors.newFixedThreadPool(2)) {
                var first = executor.submit(() -> create(payload("SAME", "First", null, managerId, true), adminToken));
                var second = executor.submit(() -> create(payload("SAME", "Second", null, secondManager, true), secondToken));
                try {
                    awaitWaiters(blockerPid, 2);
                    connection.commit();
                    var responses = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
                    assertThat(responses.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(201, 409);
                    responses.stream().filter(response -> response.statusCode() == 409)
                            .forEach(response -> assertThat(body(response).path("code").asText()).isEqualTo("DEPARTMENT_CODE_EXISTS"));
                } finally {
                    connection.rollback();
                }
            }
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM departments WHERE code = 'SAME'", Integer.class)).isEqualTo(1);
    }

    @Test
    void simultaneousDepartmentDeactivationAndAccountAssignmentHaveConsistentCommittedOutcome() throws Exception {
        account("hr@example.test", Set.of(Role.HR_MANAGER));
        String hrToken = login("hr@example.test").path("accessToken").asText();
        UUID employee = account("employee@example.test", Set.of(Role.RECRUITER));
        UUID department = department("ASSIGN", "Assignment", null, managerId, true);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blockerPid = lockRow(connection, "departments", department);
            try (var executor = Executors.newFixedThreadPool(2)) {
                var deactivate = executor.submit(() -> update(department, payload("ASSIGN", "Assignment", null, managerId, false), hrToken));
                var assign = executor.submit(() -> request("PUT", "/api/v1/accounts/" + employee,
                        json.writeValueAsString(Map.of("fullName", "Employee", "departmentId", department)), adminToken));
                try {
                    awaitWaiters(blockerPid, 2);
                    connection.commit();
                    expect(deactivate.get(10, TimeUnit.SECONDS), 200);
                    var assignment = assign.get(10, TimeUnit.SECONDS);
                    assertThat(assignment.statusCode()).isIn(200, 400);
                    UUID actual = jdbc.queryForObject("SELECT department_id FROM user_accounts WHERE id = ?", UUID.class, employee);
                    if (assignment.statusCode() == 200) {
                        assertThat(actual).isEqualTo(department);
                    } else {
                        assertThat(actual).isNull();
                        assertThat(body(assignment).path("code").asText()).isEqualTo("INVALID_DEPARTMENT");
                    }
                } finally {
                    connection.rollback();
                }
            }
        }
        assertThat(jdbc.queryForObject("SELECT active FROM departments WHERE id = ?", Boolean.class, department)).isFalse();
    }

    private UUID account(String email, Set<Role> roles) {
        return accounts.saveAndFlush(new Account(email, "Department test", fixturePasswordHash, roles, START)).getId();
    }

    private UUID department(String code, String name, UUID parent, UUID manager, boolean active) throws Exception {
        return UUID.fromString(expect(create(payload(code, name, parent, manager, active), adminToken), 201).path("id").asText());
    }

    private UUID rawDepartment(String code, UUID manager, boolean active) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO departments (id, code, name, manager_user_id, active, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                id, code, code, manager, active, Timestamp.from(START));
        return id;
    }

    private Map<String, Object> payload(String code, String name, UUID parent, UUID manager, boolean active) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", code);
        result.put("name", name);
        result.put("parentId", parent);
        result.put("managerUserId", manager);
        result.put("active", active);
        return result;
    }

    private JsonNode login(String email) throws Exception {
        return expect(request("POST", "/api/v1/auth/login", json.writeValueAsString(Map.of("email", email, "password", PASSWORD)), null), 200);
    }

    private HttpResponse<String> create(Map<String, Object> payload, String token) throws Exception {
        return request("POST", BASE, json.writeValueAsString(payload), token);
    }

    private HttpResponse<String> update(UUID id, Map<String, Object> payload, String token) throws Exception {
        return request("PUT", BASE + "/" + id, json.writeValueAsString(payload), token);
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
        return body(response);
    }

    private void error(HttpResponse<String> response, int status, String code) {
        assertThat(expect(response, status).path("code").asText()).isEqualTo(code);
    }

    private JsonNode body(HttpResponse<String> response) { return json.readTree(response.body()); }

    private void noStore(HttpResponse<String> response) {
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
    }

    private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    private List<String> ids(JsonNode array) {
        List<String> ids = new ArrayList<>();
        array.forEach(item -> ids.add(item.path("id").asText()));
        return ids;
    }

    private JsonNode find(JsonNode array, UUID id) {
        for (JsonNode node : array) {
            if (node.path("id").asText().equals(id.toString())) { return node; }
        }
        throw new AssertionError("Missing department " + id);
    }

    private int lockTree(Connection connection) throws Exception {
        try (var statement = connection.prepareStatement("SELECT pg_backend_pid(), pg_advisory_xact_lock(?)")) {
            statement.setLong(1, TREE_LOCK);
            try (var row = statement.executeQuery()) { assertThat(row.next()).isTrue(); return row.getInt(1); }
        }
    }

    private int lockRow(Connection connection, String table, UUID id) throws Exception {
        if (!Set.of("user_accounts", "departments").contains(table)) { throw new IllegalArgumentException("Unexpected fixture table"); }
        try (var statement = connection.prepareStatement("SELECT pg_backend_pid() FROM " + table + " WHERE id = ? FOR UPDATE")) {
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

    private void restoreAdminPermissions() {
        jdbc.update("""
                INSERT INTO role_permissions (role_code, permission_code)
                VALUES ('ADMIN', 'ORGANIZATION_READ_ALL'), ('ADMIN', 'ORGANIZATION_WRITE_ALL')
                ON CONFLICT DO NOTHING
                """);
    }
}
