package vn.ttcs.recruitment.auth;

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
import vn.ttcs.recruitment.requisition.RequisitionStatus;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
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

// Task 197: DELETE /api/v1/departments/{id}. Only a department nobody uses can be deleted; a department with open
// requisitions (and, like the V5 foreign keys, one with child departments or member accounts) can only be deactivated.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DepartmentDeletionIntegrationTest {
    private static final String BASE = "/api/v1/departments";
    private static final String PASSWORD = "TestingOnly123!";
    // Access tokens last 15 minutes from START; only the expiry test moves the clock.
    private static final Instant START = Instant.parse("2026-10-07T00:00:00Z");
    private static final long TREE_LOCK = 195196;
    private static final String OPEN_REQUISITIONS_MESSAGE = "Phòng ban đang có yêu cầu tuyển dụng chưa đóng nên không"
            + " thể xóa. Hãy chuyển phòng ban sang ngừng áp dụng thay vì xóa.";

    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private AccountRepository accounts;
    @Autowired private BootstrapAdmin bootstrap;
    @Autowired private AuthIntegrationTest.MutableClock clock;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private String fixturePasswordHash;
    private UUID adminId;
    private String adminToken;
    private String hrToken;
    private UUID managerId;
    private UUID positionId;

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
        jdbc.update("DELETE FROM recruitment_requisitions");
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("UPDATE user_accounts SET department_id = NULL, admin_locked_at = NULL, admin_lock_reason = NULL, admin_locked_by = NULL");
        jdbc.update("UPDATE departments SET parent_id = NULL");
        jdbc.update("DELETE FROM departments");
        jdbc.update("DELETE FROM positions");
        jdbc.update("DELETE FROM user_accounts");
        bootstrap.run(new DefaultApplicationArguments());
        JsonNode adminLogin = login("admin@example.test");
        adminId = UUID.fromString(adminLogin.path("user").path("id").asText());
        adminToken = adminLogin.path("accessToken").asText();
        fixturePasswordHash = accounts.findById(adminId).orElseThrow().getPasswordHash();
        account("hr@example.test", Set.of(Role.HR_MANAGER));
        hrToken = token("hr@example.test");
        managerId = account("manager@example.test", Set.of(Role.HIRING_MANAGER));
        positionId = position("DEV");
    }

    @Test
    void writersDeleteDepartmentsNobodyUsesAndTheyDisappearFromEveryRead() throws Exception {
        UUID root = department("ROOT", null, true);
        UUID leaf = department("LEAF", root, true);
        UUID inactive = department("OLD", null, false);
        Map<String, Object> rootBefore = row(root);
        Map<String, Object> managerBefore = jdbc.queryForMap("SELECT * FROM user_accounts WHERE id = ?", managerId);

        var response = delete(leaf, hrToken);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(204);
        assertThat(response.body()).isEmpty();
        noStore(response);
        error(get(BASE + "/" + leaf), 404, "DEPARTMENT_NOT_FOUND");
        JsonNode tree = expect(get(BASE + "/tree"), 200);
        assertThat(ids(tree)).containsExactlyInAnyOrder(root.toString(), inactive.toString());
        assertThat(find(tree, root).path("children").isEmpty()).isTrue();
        assertThat(row(root)).isEqualTo(rootBefore);

        // Deactivated or not does not matter, and the former parent has nothing left below it.
        assertThat(delete(inactive, adminToken).statusCode()).isEqualTo(204);
        assertThat(delete(root, adminToken).statusCode()).isEqualTo(204);
        assertThat(departmentCount()).isZero();
        assertThat(expect(get(BASE), 200).path("totalElements").asInt()).isZero();
        // Deleting again is a normal 404. The department manager's account is not touched.
        error(delete(leaf, adminToken), 404, "DEPARTMENT_NOT_FOUND");
        assertThat(jdbc.queryForMap("SELECT * FROM user_accounts WHERE id = ?", managerId)).isEqualTo(managerBefore);
    }

    @Test
    void aDepartmentWithADraftRequisitionCanOnlyBeDeactivated() throws Exception {
        UUID sales = department("SALES", null, true);
        UUID other = department("OTHER", null, true);
        UUID draft = requisition(sales);
        Map<String, Object> departmentBefore = row(sales);
        Map<String, Object> draftBefore = jdbc.queryForMap("SELECT * FROM recruitment_requisitions WHERE id = ?", draft);

        JsonNode refused = conflict(delete(sales, adminToken), "DEPARTMENT_HAS_OPEN_REQUISITIONS");
        assertThat(refused.path("message").asText()).isEqualTo(OPEN_REQUISITIONS_MESSAGE);
        assertThat(row(sales)).isEqualTo(departmentBefore);
        assertThat(jdbc.queryForMap("SELECT * FROM recruitment_requisitions WHERE id = ?", draft)).isEqualTo(draftBefore);

        // What the message asks for: deactivate instead. A deactivated department with a draft still cannot go.
        expect(update(sales, payload("SALES", null, false), adminToken), 200);
        conflict(delete(sales, hrToken), "DEPARTMENT_HAS_OPEN_REQUISITIONS");
        assertThat(row(sales).get("active")).isEqualTo(false);
        JsonNode stillReadable = expect(get("/api/v1/requisitions/" + draft), 200);
        assertThat(stillReadable.path("departmentId").asText()).isEqualTo(sales.toString());
        // A requisition of another department does not block this one.
        assertThat(delete(other, adminToken).statusCode()).isEqualTo(204);
    }

    // A new RequisitionStatus makes this switch fail to compile until someone decides its group. An open status must
    // answer DEPARTMENT_HAS_OPEN_REQUISITIONS (DepartmentRepository.OPEN_REQUISITION_STATUSES); a closed or cancelled
    // one is only stopped by the V13 foreign key and answers DEPARTMENT_IN_USE.
    @ParameterizedTest
    @EnumSource(RequisitionStatus.class)
    void everyRequisitionStatusBlocksDeletionWithTheCodeOfItsGroup(RequisitionStatus status) throws Exception {
        String expected = switch (status) {
            case DRAFT -> "DEPARTMENT_HAS_OPEN_REQUISITIONS";
        };
        UUID department = department("STATUS", null, true);
        jdbc.update("""
                INSERT INTO recruitment_requisitions (id, position_id, department_id, headcount, reason, status,
                                                      created_by, created_at, updated_at)
                VALUES (?, ?, ?, 1, 'NEW_HEADCOUNT', ?, ?, ?, ?)
                """, UUID.randomUUID(), positionId, department, status.name(), managerId,
                Timestamp.from(START), Timestamp.from(START));
        conflict(delete(department, adminToken), expected);
        assertThat(departmentCount()).isEqualTo(1);
    }

    @Test
    void openRequisitionsThenChildDepartmentsThenMembersBlockDeletionInThatOrder() throws Exception {
        UUID parent = department("PARENT", null, true);
        UUID child = department("CHILD", parent, true);
        UUID member = account("member@example.test", Set.of(Role.RECRUITER));
        jdbc.update("UPDATE user_accounts SET department_id = ? WHERE id = ?", parent, member);
        UUID parentDraft = requisition(parent);
        UUID childDraft = requisition(child);

        conflict(delete(parent, adminToken), "DEPARTMENT_HAS_OPEN_REQUISITIONS");
        jdbc.update("DELETE FROM recruitment_requisitions WHERE id = ?", parentDraft);
        // Only the department's own requisitions count: the child's draft blocks the child, not its parent.
        JsonNode children = conflict(delete(parent, adminToken), "DEPARTMENT_HAS_CHILDREN");
        assertThat(children.path("message").asText()).contains("phòng ban con");
        conflict(delete(child, adminToken), "DEPARTMENT_HAS_OPEN_REQUISITIONS");

        expect(update(child, payload("CHILD", null, true), adminToken), 200);
        JsonNode members = conflict(delete(parent, adminToken), "DEPARTMENT_HAS_MEMBERS");
        assertThat(members.path("message").asText()).contains("tài khoản");

        // Moving the member out through the account API (departmentId left out = no department) frees the department.
        expect(request("PUT", "/api/v1/accounts/" + member, json.writeValueAsString(Map.of("fullName", "Member")),
                adminToken), 200);
        assertThat(delete(parent, adminToken).statusCode()).isEqualTo(204);
        assertThat(departmentCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT department_id FROM recruitment_requisitions WHERE id = ?", UUID.class,
                childDraft)).isEqualTo(child);
    }

    @Test
    void aLockedAccountStillCountsAsAMember() throws Exception {
        UUID department = department("LOCKED", null, true);
        UUID member = account("locked@example.test", Set.of(Role.RECRUITER));
        jdbc.update("""
                UPDATE user_accounts SET department_id = ?, admin_locked_at = ?, admin_lock_reason = 'Review',
                                         admin_locked_by = ? WHERE id = ?
                """, department, Timestamp.from(START), adminId, member);
        conflict(delete(department, adminToken), "DEPARTMENT_HAS_MEMBERS");
        assertThat(departmentCount()).isEqualTo(1);
    }

    // The caller manages the department in every case: being its manager gives no right to delete it.
    @ParameterizedTest
    @EnumSource(Role.class)
    void onlyOrganizationWritersDeleteEvenWhenTheCallerManagesTheDepartment(Role role) throws Exception {
        UUID caller = account("caller@example.test", Set.of(role));
        String token = token("caller@example.test");
        UUID department = department("MINE", null, true, caller);
        Map<String, Object> before = row(department);
        var response = delete(department, token);
        if (role == Role.ADMIN || role == Role.HR_MANAGER) {
            assertThat(response.statusCode()).as(response.body()).isEqualTo(204);
            assertThat(departmentCount()).isZero();
        } else {
            forbidden(response);
            assertThat(row(department)).isEqualTo(before);
        }
    }

    @Test
    void anonymousUnknownMalformedAndNoLongerPermittedCallsDeleteNothing() throws Exception {
        UUID department = department("KEEP", null, true);
        assertThat(delete(department, null).statusCode()).isEqualTo(401);
        error(delete(UUID.randomUUID(), adminToken), 404, "DEPARTMENT_NOT_FOUND");
        error(request("DELETE", BASE + "/not-a-uuid", null, adminToken), 400, "VALIDATION_ERROR");
        // Permission comes before existence: a reader gets 403 even for an unknown id.
        account("reader@example.test", Set.of(Role.INTERVIEWER));
        forbidden(delete(UUID.randomUUID(), token("reader@example.test")));
        // The permission is read from the database on every request, so the same access token loses it at once.
        jdbc.update("DELETE FROM role_permissions WHERE role_code = 'ADMIN' AND permission_code = 'ORGANIZATION_WRITE_ALL'");
        try {
            forbidden(delete(department, adminToken));
        } finally {
            restoreAdminPermissions();
        }
        assertThat(departmentCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"lost-permission", "expired-jwt"})
    void rechecksPermissionAndExpiryAfterWaitingForTheTreeLock(String change) throws Exception {
        UUID department = department("WAIT", null, true);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blockerPid = lockTree(connection);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(() -> delete(department, adminToken));
                try {
                    awaitWaiters(blockerPid, 1);
                    if (change.equals("lost-permission")) {
                        jdbc.update("DELETE FROM role_permissions WHERE role_code = 'ADMIN' AND permission_code = 'ORGANIZATION_WRITE_ALL'");
                    } else {
                        clock.set(START.plus(Duration.ofMinutes(15)));
                    }
                    connection.commit();
                    var result = response.get(10, TimeUnit.SECONDS);
                    if (change.equals("lost-permission")) {
                        forbidden(result);
                    } else {
                        error(result, 401, "SESSION_INVALID");
                    }
                } finally {
                    connection.rollback();
                }
            }
        } finally {
            restoreAdminPermissions();
        }
        assertThat(departmentCount()).isEqualTo(1);
    }

    // The other connection does what RequisitionService and AccountManagementService do: read the department
    // FOR SHARE, then save a row that points at it. The delete must wait for that transaction and then see its result.
    @ParameterizedTest
    @CsvSource({
            "requisition, commit, DEPARTMENT_HAS_OPEN_REQUISITIONS",
            "requisition, rollback,",
            "member, commit, DEPARTMENT_HAS_MEMBERS",
            "member, rollback,"
    })
    void aReferenceSavedWhileTheDeleteWaitsIsSeenByTheChecks(String reference, String outcome, String code)
            throws Exception {
        UUID department = department("RACE", null, true);
        // Not the deleting admin: the other transaction must not hold a lock on the admin's account row.
        UUID employee = account("employee@example.test", Set.of(Role.RECRUITER));
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blockerPid = backendPid(connection);
            assertThat(execute(connection, "SELECT 1 FROM departments WHERE id = ? FOR SHARE", department)).isEqualTo(1);
            if (reference.equals("requisition")) {
                assertThat(execute(connection, """
                        INSERT INTO recruitment_requisitions (id, position_id, department_id, headcount, reason,
                                                              created_by, created_at, updated_at)
                        VALUES (?, ?, ?, 1, 'NEW_HEADCOUNT', ?, ?, ?)
                        """, UUID.randomUUID(), positionId, department, managerId,
                        Timestamp.from(START), Timestamp.from(START))).isEqualTo(1);
            } else {
                assertThat(execute(connection, "UPDATE user_accounts SET department_id = ? WHERE id = ?",
                        department, employee)).isEqualTo(1);
            }
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(() -> delete(department, adminToken));
                try {
                    awaitWaiters(blockerPid, 1);
                    if (outcome.equals("commit")) {
                        connection.commit();
                    } else {
                        connection.rollback();
                    }
                    var result = response.get(10, TimeUnit.SECONDS);
                    if (code == null) {
                        assertThat(result.statusCode()).as(result.body()).isEqualTo(204);
                    } else {
                        conflict(result, code);
                    }
                } finally {
                    connection.rollback();
                }
            }
        }
        assertThat(departmentCount()).isEqualTo(code == null ? 0 : 1);
    }

    // The delete holds the tree lock while it waits for the department row (held FOR SHARE by the other connection).
    // HR moving another department under it meanwhile waits for the tree lock and then finds no parent, instead of
    // creating a child the delete did not see.
    @Test
    void aParentChangeToADepartmentBeingDeletedWaitsAndThenFindsNoParent() throws Exception {
        UUID target = department("TARGET", null, true);
        UUID other = department("OTHER", null, true);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blockerPid = backendPid(connection);
            assertThat(execute(connection, "SELECT 1 FROM departments WHERE id = ? FOR SHARE", target)).isEqualTo(1);
            try (var executor = Executors.newFixedThreadPool(2)) {
                var deletion = executor.submit(() -> delete(target, adminToken));
                try {
                    awaitWaiters(blockerPid, 1);
                    // HR, not the deleting admin: the move must wait for the tree lock, not for the admin's account.
                    var move = executor.submit(() -> update(other, payload("OTHER", target, true), hrToken));
                    awaitWaiters(blockerPid, 2);
                    connection.commit();
                    assertThat(deletion.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(204);
                    error(move.get(10, TimeUnit.SECONDS), 400, "INVALID_DEPARTMENT_PARENT");
                } finally {
                    connection.rollback();
                }
            }
        }
        assertThat(row(other).get("parent_id")).isNull();
        assertThat(departmentCount()).isEqualTo(1);
    }

    // The other side of the race: the other connection deletes the department the way DELETE /departments/{id} does
    // after its checks (the row is locked until commit). A requisition or an account assignment saved for it
    // meanwhile waits, then gets the normal form error instead of a foreign key failure (500).
    @ParameterizedTest
    @CsvSource({
            "requisition, INVALID_REQUISITION_DEPARTMENT",
            "member, INVALID_DEPARTMENT"
    })
    void aReferenceSavedWhileItsDepartmentIsBeingDeletedGetsAFormError(String reference, String code)
            throws Exception {
        UUID department = department("GONE", null, true);
        UUID employee = account("employee@example.test", Set.of(Role.RECRUITER));
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blockerPid = backendPid(connection);
            assertThat(execute(connection, "DELETE FROM departments WHERE id = ?", department)).isEqualTo(1);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(() -> reference.equals("requisition")
                        ? request("POST", "/api/v1/requisitions", json.writeValueAsString(requisitionBody(department)),
                                hrToken)
                        : request("PUT", "/api/v1/accounts/" + employee, json.writeValueAsString(
                                Map.of("fullName", "Employee", "departmentId", department)), adminToken));
                try {
                    awaitWaiters(blockerPid, 1);
                    connection.commit();
                    error(response.get(10, TimeUnit.SECONDS), 400, code);
                } finally {
                    connection.rollback();
                }
            }
        }
        assertThat(departmentCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recruitment_requisitions", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT department_id FROM user_accounts WHERE id = ?", UUID.class, employee))
                .isNull();
    }

    // Stands for a reference the explicit checks do not know: a closed requisition once the approval workflow adds
    // closed statuses, or a table added later. The probe table only exists during this test.
    @Test
    void anotherRowStillPointingAtTheDepartmentIsAConflictNotAServerError() throws Exception {
        UUID department = department("REF", null, true);
        jdbc.execute("CREATE TABLE department_delete_probe (department_id UUID NOT NULL REFERENCES departments(id))");
        try {
            jdbc.update("INSERT INTO department_delete_probe (department_id) VALUES (?)", department);
            JsonNode body = conflict(delete(department, adminToken), "DEPARTMENT_IN_USE");
            assertThat(body.path("message").asText()).contains("ngừng áp dụng");
            assertThat(departmentCount()).isEqualTo(1);
        } finally {
            jdbc.execute("DROP TABLE IF EXISTS department_delete_probe");
        }
    }

    private UUID account(String email, Set<Role> roles) {
        return accounts.saveAndFlush(new Account(email, "Deletion test", fixturePasswordHash, roles, START)).getId();
    }

    private UUID department(String code, UUID parent, boolean active) throws Exception {
        return department(code, parent, active, managerId);
    }

    private UUID department(String code, UUID parent, boolean active, UUID manager) throws Exception {
        Map<String, Object> body = payload(code, parent, active);
        body.put("managerUserId", manager);
        return UUID.fromString(expect(request("POST", BASE, json.writeValueAsString(body), adminToken), 201)
                .path("id").asText());
    }

    private Map<String, Object> payload(String code, UUID parent, boolean active) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", code);
        result.put("name", "Phòng " + code);
        result.put("parentId", parent);
        result.put("managerUserId", managerId);
        result.put("active", active);
        return result;
    }

    private UUID position(String code) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO positions (id, code, name, level, salary_min, salary_max, active, created_at, updated_at)
                VALUES (?, ?, 'Lập trình viên', 'Junior', 15000000, 25000000, TRUE, ?, ?)
                """, id, code, Timestamp.from(START), Timestamp.from(START));
        return id;
    }

    private Map<String, Object> requisitionBody(UUID department) {
        return Map.of("positionId", positionId, "departmentId", department, "headcount", 1, "reason", "NEW_HEADCOUNT");
    }

    // HR_MANAGER has REQUISITIONS_WRITE_ALL, so it saves a draft for any department.
    private UUID requisition(UUID department) throws Exception {
        return UUID.fromString(expect(request("POST", "/api/v1/requisitions",
                json.writeValueAsString(requisitionBody(department)), hrToken), 201).path("id").asText());
    }

    private Map<String, Object> row(UUID department) {
        return jdbc.queryForMap("SELECT * FROM departments WHERE id = ?", department);
    }

    private int departmentCount() {
        return jdbc.queryForObject("SELECT count(*) FROM departments", Integer.class);
    }

    private JsonNode login(String email) throws Exception {
        return expect(request("POST", "/api/v1/auth/login",
                json.writeValueAsString(Map.of("email", email, "password", PASSWORD)), null), 200);
    }

    private String token(String email) throws Exception {
        return login(email).path("accessToken").asText();
    }

    private HttpResponse<String> delete(UUID id, String token) throws Exception {
        return request("DELETE", BASE + "/" + id, null, token);
    }

    private HttpResponse<String> update(UUID id, Map<String, Object> payload, String token) throws Exception {
        return request("PUT", BASE + "/" + id, json.writeValueAsString(payload), token);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return request("GET", path, null, adminToken);
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

    // A 409 with this code, a Vietnamese message, no field errors (the problem is not one form field) and no-store.
    private JsonNode conflict(HttpResponse<String> response, String code) {
        JsonNode body = expect(response, 409);
        assertThat(body.path("code").asText()).isEqualTo(code);
        assertThat(body.path("message").asText()).contains("không thể xóa");
        assertThat(body.path("fieldErrors").isEmpty()).as(body.toString()).isTrue();
        noStore(response);
        return body;
    }

    private void forbidden(HttpResponse<String> response) {
        JsonNode body = expect(response, 403);
        assertThat(body.path("code").asText()).isEqualTo("FORBIDDEN");
        assertThat(body.path("message").asText()).isEqualTo("Bạn không có quyền thực hiện thao tác này.");
        noStore(response);
    }

    private void noStore(HttpResponse<String> response) {
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
    }

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

    private int lockTree(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT pg_backend_pid(), pg_advisory_xact_lock(?)")) {
            statement.setLong(1, TREE_LOCK);
            try (var row = statement.executeQuery()) { assertThat(row.next()).isTrue(); return row.getInt(1); }
        }
    }

    private int backendPid(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT pg_backend_pid()");
             var row = statement.executeQuery()) {
            assertThat(row.next()).isTrue();
            return row.getInt(1);
        }
    }

    // Runs one statement in the other connection's open transaction. For a SELECT it returns the number of rows.
    private int execute(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) { statement.setObject(index + 1, values[index]); }
            if (!statement.execute()) {
                return statement.getUpdateCount();
            }
            int rows = 0;
            try (var result = statement.getResultSet()) {
                while (result.next()) { rows++; }
            }
            return rows;
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
                VALUES ('ADMIN', 'ORGANIZATION_WRITE_ALL')
                ON CONFLICT DO NOTHING
                """);
    }
}
