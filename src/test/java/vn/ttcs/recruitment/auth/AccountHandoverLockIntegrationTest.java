package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// Feedback item 10: what a handover of an account's requisitions and departments does when it has to wait for a lock,
// and when it runs at the same time as an assignment, a lock of the recipient, a department write or another handover.
// Same patterns as RequisitionRecruiterLockIntegrationTest and DepartmentManagementIntegrationTest.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AccountHandoverLockIntegrationTest {
    private static final String PASSWORD = "TestingOnly123!";
    private static final Instant START = Instant.parse("2026-10-16T03:00:00Z");
    private static final String NOTE = "Nghỉ việc";
    // DepartmentRepository.TREE_WRITE_LOCK_KEY, the advisory lock of every department write.
    private static final long TREE_LOCK = 195196;

    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private AccountRepository accounts;
    @Autowired private BootstrapAdmin bootstrap;
    @Autowired private AuthIntegrationTest.MutableClock clock;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String loginDeviceId = UUID.randomUUID().toString();
    private List<Map<String, Object>> permissionSeed;
    private String fixturePasswordHash;
    private UUID adminId;
    private String adminToken;
    private String otherAdminToken;
    private String hrToken;
    private UUID targetId;
    private UUID departmentId;
    // A department the account manages.
    private UUID salesId;
    private UUID positionId;
    private UUID requisitionId;
    private final List<UUID> r = new ArrayList<>();

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
        permissionSeed = jdbc.queryForList("SELECT role_code, permission_code FROM role_permissions");
        jdbc.execute("TRUNCATE requisition_workflow_events, approval_flow_steps, approval_flow_versions, approval_flows");
        jdbc.update("DELETE FROM requisition_recruiter_changes");
        jdbc.update("DELETE FROM requisition_recruiters");
        jdbc.update("DELETE FROM recruitment_requisitions");
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("UPDATE user_accounts SET department_id = NULL, admin_locked_at = NULL, admin_lock_reason = NULL, admin_locked_by = NULL");
        jdbc.update("UPDATE departments SET parent_id = NULL");
        jdbc.update("DELETE FROM departments");
        jdbc.update("DELETE FROM positions");
        jdbc.update("DELETE FROM user_accounts");
        bootstrap.run(new DefaultApplicationArguments());
        adminId = jdbc.queryForObject("SELECT id FROM user_accounts WHERE email = 'admin@example.test'", UUID.class);
        fixturePasswordHash = accounts.findById(adminId).orElseThrow().getPasswordHash();
        adminToken = token("admin@example.test");
        account("admin2@example.test", Set.of(Role.ADMIN));
        otherAdminToken = token("admin2@example.test");
        UUID hrId = account("hr@example.test", Set.of(Role.HR_MANAGER));
        hrToken = token("hr@example.test");
        targetId = account("target@example.test", Set.of(Role.RECRUITER));
        r.clear();
        for (int i = 0; i < 4; i++) {
            r.add(account("r" + i + "@example.test", Set.of(Role.RECRUITER)));
        }
        departmentId = UUID.randomUUID();
        jdbc.update("INSERT INTO departments (id, code, name, manager_user_id, active, created_at) VALUES (?, 'IT', 'IT', ?, TRUE, ?)",
                departmentId, hrId, Timestamp.from(START));
        salesId = UUID.randomUUID();
        jdbc.update("INSERT INTO departments (id, code, name, manager_user_id, active, created_at) VALUES (?, 'SALES', 'Sales', ?, TRUE, ?)",
                salesId, targetId, Timestamp.from(START));
        positionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO positions (id, code, name, level, salary_min, salary_max, active, created_at, updated_at)
                VALUES (?, 'DEV', 'Lập trình viên', 'Junior', 15000000, 25000000, TRUE, ?, ?)
                """, positionId, Timestamp.from(START), Timestamp.from(START));
        requisitionId = requisition();
        recruiter(requisitionId, targetId, "PRIMARY");
    }

    @AfterEach
    void restorePermissionSeed() {
        if (permissionSeed == null) { return; }
        jdbc.update("DELETE FROM role_permissions");
        jdbc.batchUpdate("INSERT INTO role_permissions (role_code, permission_code) VALUES (?, ?)", permissionSeed.stream()
                .map(row -> new Object[] {row.get("role_code"), row.get("permission_code")}).toList());
    }

    // The handover waits for the requisition row (a save or an assignment holds it), for the account of the
    // recipient or of the account handed over (a role change or a lock holds it) or for the caller's own account (an
    // admin lock or a logout holds it). Whatever changed meanwhile is checked once the lock is granted. The account
    // hands its primary role over to r0.
    @ParameterizedTest(name = "{0} held, then {1}")
    @CsvSource({
            "requisition, nothing, 200", "requisition, token-expired, 401", "requisition, permission-lost, 403",
            "recipient, nothing, 200", "recipient, token-expired, 401", "recipient, permission-lost, 403",
            "target, nothing, 200", "target, token-expired, 401",
            "actor, locked-actor, 401", "actor, revoked-session, 401"
    })
    void theCallerIsCheckedAgainAfterEachLockWait(String held, String change, int expected) throws Exception {
        String sql = held.equals("requisition")
                ? "SELECT pg_backend_pid() FROM recruitment_requisitions WHERE id = ? FOR SHARE"
                : "SELECT pg_backend_pid() FROM user_accounts WHERE id = ? FOR SHARE";
        UUID heldId = switch (held) {
            case "requisition" -> requisitionId;
            case "recipient" -> r.get(0);
            case "target" -> targetId;
            default -> adminId;
        };
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blocker = lock(connection, sql, heldId);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(() -> handOver(targetId, requisitionId, r.get(0), adminToken));
                awaitWaiters(blocker, 1);
                switch (change) {
                    case "token-expired" -> clock.set(START.plus(Duration.ofMinutes(16)));
                    case "permission-lost" -> jdbc.update("DELETE FROM role_permissions WHERE role_code = 'ADMIN' "
                            + "AND permission_code = 'REQUISITIONS_WRITE_ALL'");
                    case "locked-actor" -> execute(connection, "UPDATE user_accounts SET admin_locked_at = ?, "
                            + "admin_lock_reason = 'Review', admin_locked_by = ? WHERE id = ?",
                            Timestamp.from(START), adminId, adminId);
                    case "revoked-session" -> execute(connection,
                            "UPDATE auth_sessions SET revoked_at = ? WHERE user_id = ?", Timestamp.from(START), adminId);
                    default -> { }
                }
                connection.commit();
                var result = response.get(15, TimeUnit.SECONDS);
                assertThat(result.statusCode()).as(result.body()).isEqualTo(expected);
                if (expected == 401) {
                    assertThat(json.readTree(result.body()).path("code").asText()).isEqualTo("SESSION_INVALID");
                    // Sent by ApiExceptionHandler: no WWW-Authenticate, but no-store from Spring Security's defaults.
                    assertThat(result.headers().firstValue("Cache-Control").orElse("<none>")).contains("no-store");
                    assertThat(result.headers().firstValue("WWW-Authenticate")).isEmpty();
                }
            }
        }
        assertThat(team(requisitionId)).containsExactly((expected == 200 ? r.get(0) : targetId) + ":PRIMARY");
        assertThat(historyRows()).isEqualTo(expected == 200 ? 1 : 0);
    }

    // A role change or a lock of the recipient locks its account like this, then changes it.
    @Test
    void aRecipientLockedByAnAdminWhileTheHandoverWaitsIsRefused() throws Exception {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blocker = lock(connection, "SELECT pg_backend_pid() FROM user_accounts WHERE id = ? FOR NO KEY UPDATE",
                    r.get(0));
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(() -> handOver(targetId, requisitionId, r.get(0), adminToken));
                awaitWaiters(blocker, 1);
                execute(connection, "UPDATE user_accounts SET admin_locked_at = ?, admin_lock_reason = 'Review', "
                        + "admin_locked_by = ? WHERE id = ?", Timestamp.from(START), adminId, r.get(0));
                connection.commit();
                var result = response.get(15, TimeUnit.SECONDS);
                assertThat(result.statusCode()).as(result.body()).isEqualTo(400);
                JsonNode error = json.readTree(result.body());
                assertThat(error.path("code").asText()).isEqualTo("REQUISITION_RECRUITER_INACTIVE");
                assertThat(error.path("fieldErrors").has("requisitions[0].toUserId")).isTrue();
            }
        }
        assertThat(team(requisitionId)).containsExactly(targetId + ":PRIMARY");
        assertThat(historyRows()).isZero();
    }

    // Another administrator locks the recipient through the lock API at the same moment. Whichever runs second sees
    // the first one's result: the lock first, then the handover is refused; the handover first, then the recipient is
    // locked while it holds the requisition (eligible: false), like any recruiter locked after an assignment.
    @Test
    void aRecipientLockedAtTheSameTimeEitherRefusesTheHandoverOrIsLockedAfterIt() throws Exception {
        var results = concurrently("SELECT pg_backend_pid() FROM user_accounts WHERE id = ? FOR SHARE", r.get(0),
                () -> handOver(targetId, requisitionId, r.get(0), adminToken),
                () -> request("PUT", "/api/v1/accounts/" + r.get(0) + "/lock",
                        json.writeValueAsString(Map.of("reason", "Nghỉ việc")), otherAdminToken));
        assertThat(results.get(1).statusCode()).as(results.get(1).body()).isEqualTo(200);
        var handover = results.get(0);
        if (handover.statusCode() == 200) {
            assertThat(team(requisitionId)).containsExactly(r.get(0) + ":PRIMARY");
        } else {
            assertThat(handover.statusCode()).as(handover.body()).isEqualTo(400);
            assertThat(json.readTree(handover.body()).path("code").asText()).isEqualTo("REQUISITION_RECRUITER_INACTIVE");
            assertThat(team(requisitionId)).containsExactly(targetId + ":PRIMARY");
        }
        assertThat(jdbc.queryForObject("SELECT admin_locked_at IS NOT NULL FROM user_accounts WHERE id = ?",
                Boolean.class, r.get(0))).isTrue();
    }

    // HR hands the requisition over to r1 while an administrator hands the account's requisitions over to r2. Both
    // take the requisition row before they read the team, so they run one after the other: the assignment always
    // succeeds; the handover succeeds only when it runs first, and is HANDOVER_ITEM_NOT_HELD otherwise.
    @Test
    void anAssignmentAndAHandoverOfTheSameRequisitionRunOneAfterTheOther() throws Exception {
        var results = concurrently("SELECT pg_backend_pid() FROM recruitment_requisitions WHERE id = ? FOR SHARE",
                requisitionId,
                () -> request("POST", "/api/v1/requisitions/" + requisitionId + "/assign",
                        json.writeValueAsString(Map.of("recruiterId", r.get(1))), hrToken),
                () -> handOver(targetId, requisitionId, r.get(2), adminToken));
        assertThat(results.get(0).statusCode()).as(results.get(0).body()).isEqualTo(200);
        var handover = results.get(1);
        List<String> history = history(requisitionId);
        if (handover.statusCode() == 200) {
            assertThat(history).containsExactly("PRIMARY_HANDED_OVER:" + r.get(2) + ":" + targetId,
                    "PRIMARY_HANDED_OVER:" + r.get(1) + ":" + r.get(2));
        } else {
            assertThat(handover.statusCode()).as(handover.body()).isEqualTo(409);
            assertThat(json.readTree(handover.body()).path("code").asText()).isEqualTo("HANDOVER_ITEM_NOT_HELD");
            assertThat(history).containsExactly("PRIMARY_HANDED_OVER:" + r.get(1) + ":" + targetId);
        }
        assertThat(team(requisitionId)).containsExactly(r.get(1) + ":PRIMARY");
    }

    // Two administrators hand over the work of two accounts that share two requisitions, naming them in opposite
    // orders. Both lock the requisitions in id order, so neither waits for a row the other one holds while holding a
    // row it needs: both succeed, one after the other.
    @Test
    void twoHandoversThatShareRequisitionsDoNotDeadlock() throws Exception {
        UUID supporting = r.get(3);
        UUID second = requisition();
        recruiter(second, targetId, "PRIMARY");
        recruiter(requisitionId, supporting, "SUPPORTING");
        recruiter(second, supporting, "SUPPORTING");
        List<UUID> ascending = new ArrayList<>(List.of(requisitionId, second));
        ascending.sort(Comparator.comparing(UUID::toString));
        List<UUID> descending = List.of(ascending.get(1), ascending.get(0));

        var results = concurrently(
                "SELECT pg_backend_pid() FROM recruitment_requisitions WHERE id IN (?, ?) ORDER BY id FOR SHARE",
                List.of(requisitionId, second),
                () -> handOver(targetId, descending, r.get(1), adminToken),
                () -> handOver(supporting, ascending, r.get(2), otherAdminToken));

        assertThat(results).allSatisfy(result -> assertThat(result.statusCode()).as(result.body()).isEqualTo(200));
        for (UUID requisition : List.of(requisitionId, second)) {
            assertThat(team(requisition)).containsExactly(r.get(1) + ":PRIMARY", r.get(2) + ":SUPPORTING");
        }
    }

    // The body is refused before the handover locks anything: these answer while another transaction holds every row
    // the handover would lock.
    @Test
    void aRefusedBodyAnswersWithoutWaitingForAnyLock() throws Exception {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            for (UUID account : List.of(adminId, targetId, r.get(0))) {
                lock(connection, "SELECT pg_backend_pid() FROM user_accounts WHERE id = ? FOR UPDATE", account);
            }
            lock(connection, "SELECT pg_backend_pid() FROM recruitment_requisitions WHERE id = ? FOR UPDATE",
                    requisitionId);
            lockTree(connection);
            lock(connection, "SELECT pg_backend_pid() FROM departments WHERE id = ? FOR UPDATE", salesId);
            try (var executor = Executors.newFixedThreadPool(3)) {
                var self = executor.submit(() -> handOver(targetId, requisitionId, targetId, adminToken));
                var empty = executor.submit(() -> request("POST", "/api/v1/accounts/" + targetId + "/handover", "{}",
                        adminToken));
                var selfDepartment = executor.submit(() -> handOverDepartment(targetId, salesId, targetId, adminToken));
                for (Future<HttpResponse<String>> response : List.of(self, empty, selfDepartment)) {
                    var result = response.get(5, TimeUnit.SECONDS);
                    assertThat(result.statusCode()).as(result.body()).isEqualTo(400);
                    assertThat(json.readTree(result.body()).path("code").asText()).isEqualTo("VALIDATION_ERROR");
                }
            }
            connection.rollback();
        }
        assertThat(team(requisitionId)).containsExactly(targetId + ":PRIMARY");
        assertThat(manager(salesId)).isEqualTo(targetId);
    }

    // A handover of departments takes the tree lock of the department writes before any row, then the department rows.
    // It may wait for either: a POST, PUT or DELETE of /departments holds the tree, a requisition save or an account
    // assignment of the department holds its row FOR SHARE. Whatever changed meanwhile is checked once the row is held.
    @ParameterizedTest(name = "{0} held, then {1}")
    @CsvSource({
            "tree, nothing, 200", "tree, token-expired, 401", "tree, permission-lost, 403",
            "department, nothing, 200", "department, token-expired, 401", "department, permission-lost, 403"
    })
    void aDepartmentHandoverChecksTheCallerAgainAfterWaitingForTheTreeOrTheDepartment(String held, String change,
                                                                                      int expected) throws Exception {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blocker = held.equals("tree") ? lockTree(connection)
                    : lock(connection, "SELECT pg_backend_pid() FROM departments WHERE id = ? FOR SHARE", salesId);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(() -> handOverDepartment(targetId, salesId, r.get(0), adminToken));
                awaitWaiters(blocker, 1);
                switch (change) {
                    case "token-expired" -> clock.set(START.plus(Duration.ofMinutes(16)));
                    case "permission-lost" -> jdbc.update("DELETE FROM role_permissions WHERE role_code = 'ADMIN' "
                            + "AND permission_code = 'ORGANIZATION_WRITE_ALL'");
                    default -> { }
                }
                connection.commit();
                var result = response.get(15, TimeUnit.SECONDS);
                assertThat(result.statusCode()).as(result.body()).isEqualTo(expected);
                if (expected == 401) {
                    assertThat(json.readTree(result.body()).path("code").asText()).isEqualTo("SESSION_INVALID");
                }
            }
        }
        assertThat(manager(salesId)).isEqualTo(expected == 200 ? r.get(0) : targetId);
    }

    // HR gives the department another manager (PUT /departments/{id}) while an administrator hands the account's
    // departments over. Both take the tree lock first, so they run one after the other, in the order they asked for
    // it, and the second sees what the first committed: after the PUT the account no longer manages the department
    // (HANDOVER_ITEM_NOT_HELD); after the handover the PUT replaces the manager the handover gave it.
    @ParameterizedTest(name = "{0} first")
    @CsvSource({"put", "handover"})
    void aDepartmentWriteAndADepartmentHandoverRunOneAfterTheOther(String first) throws Exception {
        Callable<HttpResponse<String>> put = () -> request("PUT", "/api/v1/departments/" + salesId,
                json.writeValueAsString(Map.of("code", "SALES", "name", "Kinh doanh", "managerUserId", r.get(1),
                        "active", true)), hrToken);
        Callable<HttpResponse<String>> handover = () -> handOverDepartment(targetId, salesId, r.get(0), adminToken);
        HttpResponse<String> putResult;
        HttpResponse<String> handoverResult;
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blocker = lockTree(connection);
            try (var executor = Executors.newFixedThreadPool(2)) {
                // The second call asks for the tree only once the first one waits for it.
                Future<HttpResponse<String>> firstResponse = executor.submit(first.equals("put") ? put : handover);
                awaitWaiters(blocker, 1);
                Future<HttpResponse<String>> secondResponse = executor.submit(first.equals("put") ? handover : put);
                awaitWaiters(blocker, 2);
                connection.commit();
                var results = List.of(firstResponse.get(20, TimeUnit.SECONDS),
                        secondResponse.get(20, TimeUnit.SECONDS));
                putResult = results.get(first.equals("put") ? 0 : 1);
                handoverResult = results.get(first.equals("put") ? 1 : 0);
            }
        }
        assertThat(putResult.statusCode()).as(putResult.body()).isEqualTo(200);
        if (first.equals("put")) {
            assertThat(handoverResult.statusCode()).as(handoverResult.body()).isEqualTo(409);
            JsonNode error = json.readTree(handoverResult.body());
            assertThat(error.path("code").asText()).isEqualTo("HANDOVER_ITEM_NOT_HELD");
            assertThat(error.path("fieldErrors").has("departments[0].departmentId")).isTrue();
        } else {
            assertThat(handoverResult.statusCode()).as(handoverResult.body()).isEqualTo(200);
        }
        assertThat(manager(salesId)).isEqualTo(r.get(1));
        assertThat(jdbc.queryForObject("SELECT name FROM departments WHERE id = ?", String.class, salesId))
                .isEqualTo("Kinh doanh");
    }

    // A requisition save or an approval decision locks the requisition row, then reads its department FOR SHARE. A
    // handover of that requisition and that department locks the requisition rows before the department rows, so it
    // waits at the requisition without holding the department: the save takes the department at once and commits, and
    // the handover goes on. Locking the department rows first would deadlock here (40P01).
    @Test
    void aHandoverOfBothListsWaitsForARequisitionSaveOfTheDepartmentWithoutDeadlock() throws Exception {
        jdbc.update("UPDATE recruitment_requisitions SET department_id = ? WHERE id = ?", salesId, requisitionId);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blocker = lock(connection,
                    "SELECT pg_backend_pid() FROM recruitment_requisitions WHERE id = ? FOR UPDATE", requisitionId);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(() -> request("POST", "/api/v1/accounts/" + targetId + "/handover",
                        json.writeValueAsString(Map.of("note", NOTE,
                                "requisitions", List.of(Map.of("requisitionId", requisitionId, "toUserId", r.get(0))),
                                "departments", List.of(Map.of("departmentId", salesId, "toUserId", r.get(1))))),
                        adminToken));
                awaitWaiters(blocker, 1);
                lock(connection, "SELECT pg_backend_pid() FROM departments WHERE id = ? FOR SHARE", salesId);
                connection.commit();
                var result = response.get(15, TimeUnit.SECONDS);
                assertThat(result.statusCode()).as(result.body()).isEqualTo(200);
                assertThat(json.readTree(result.body()).path("transferred").asInt()).isEqualTo(2);
            }
        }
        assertThat(team(requisitionId)).containsExactly(r.get(0) + ":PRIMARY");
        assertThat(manager(salesId)).isEqualTo(r.get(1));
    }

    // Holds the given rows so every call reaches them, then lets them run one after the other.
    @SafeVarargs
    private List<HttpResponse<String>> concurrently(String sql, Object ids, Callable<HttpResponse<String>>... calls)
            throws Exception {
        List<HttpResponse<String>> results = new ArrayList<>();
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blocker;
            try (var statement = connection.prepareStatement(sql)) {
                List<?> values = ids instanceof List<?> list ? list : List.of(ids);
                for (int index = 0; index < values.size(); index++) { statement.setObject(index + 1, values.get(index)); }
                try (var row = statement.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    blocker = row.getInt(1);
                }
            }
            try (var executor = Executors.newFixedThreadPool(calls.length)) {
                List<Future<HttpResponse<String>>> responses = new ArrayList<>();
                for (var call : calls) {
                    responses.add(executor.submit(call));
                }
                awaitWaiters(blocker, calls.length);
                connection.commit();
                for (var response : responses) {
                    results.add(response.get(20, TimeUnit.SECONDS));
                }
            }
        }
        return results;
    }

    private HttpResponse<String> handOver(UUID account, UUID requisition, UUID toUserId, String token) throws Exception {
        return handOver(account, List.of(requisition), toUserId, token);
    }

    private HttpResponse<String> handOver(UUID account, List<UUID> requisitions, UUID toUserId, String token)
            throws Exception {
        var items = requisitions.stream().map(id -> Map.of("requisitionId", id, "toUserId", toUserId)).toList();
        return request("POST", "/api/v1/accounts/" + account + "/handover",
                json.writeValueAsString(Map.of("note", NOTE, "requisitions", items)), token);
    }

    private HttpResponse<String> handOverDepartment(UUID account, UUID department, UUID toUserId, String token)
            throws Exception {
        return request("POST", "/api/v1/accounts/" + account + "/handover", json.writeValueAsString(Map.of("note", NOTE,
                "departments", List.of(Map.of("departmentId", department, "toUserId", toUserId)))), token);
    }

    private UUID manager(UUID department) {
        return jdbc.queryForObject("SELECT manager_user_id FROM departments WHERE id = ?", UUID.class, department);
    }

    private int lockTree(Connection connection) throws Exception {
        try (var statement = connection.prepareStatement("SELECT pg_backend_pid(), pg_advisory_xact_lock(?)")) {
            statement.setLong(1, TREE_LOCK);
            try (var row = statement.executeQuery()) { assertThat(row.next()).isTrue(); return row.getInt(1); }
        }
    }

    private UUID requisition() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO recruitment_requisitions (id, position_id, department_id, headcount, reason, status,
                    created_by, created_at, updated_at)
                VALUES (?, ?, ?, 1, 'NEW_HEADCOUNT', 'APPROVED', ?, ?, ?)
                """, id, positionId, departmentId, adminId, Timestamp.from(START), Timestamp.from(START));
        return id;
    }

    private void recruiter(UUID requisition, UUID recruiter, String role) {
        jdbc.update("""
                INSERT INTO requisition_recruiters (requisition_id, recruiter_id, assignment_role, assigned_by, assigned_at)
                VALUES (?, ?, ?, ?, ?)
                """, requisition, recruiter, role, adminId, Timestamp.from(START.minusSeconds(3600)));
    }

    private List<String> team(UUID requisition) {
        return jdbc.queryForList("""
                SELECT recruiter_id::text || ':' || assignment_role FROM requisition_recruiters
                WHERE requisition_id = ? ORDER BY assignment_role, recruiter_id
                """, String.class, requisition);
    }

    private List<String> history(UUID requisition) {
        return jdbc.queryForList("""
                SELECT change_type || ':' || recruiter_id || ':' || COALESCE(previous_recruiter_id::text, '-')
                FROM requisition_recruiter_changes WHERE requisition_id = ? ORDER BY revision
                """, String.class, requisition);
    }

    private int historyRows() {
        return jdbc.queryForObject("SELECT count(*) FROM requisition_recruiter_changes", Integer.class);
    }

    private UUID account(String email, Set<Role> roles) {
        return accounts.saveAndFlush(new Account(email, "Lock test", fixturePasswordHash, roles, START)).getId();
    }

    private String token(String email) throws Exception {
        return expect(request("POST", "/api/v1/auth/login",
                json.writeValueAsString(Map.of("email", email, "password", PASSWORD)), null), 200)
                .path("accessToken").asText();
    }

    private HttpResponse<String> request(String method, String path, String payload, String token) throws Exception {
        URI uri = URI.create("http://127.0.0.1:" + environment.getRequiredProperty("local.server.port") + path);
        var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                .method(method, payload == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(payload));
        if (token != null) { builder.header("Authorization", "Bearer " + token); }
        if ("POST".equals(method) && "/api/v1/auth/login".equals(path)) {
            builder.header("X-Device-Id", loginDeviceId);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode expect(HttpResponse<String> response, int expected) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expected);
        return json.readTree(response.body());
    }

    private int lock(Connection connection, String sql, UUID id) throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
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
