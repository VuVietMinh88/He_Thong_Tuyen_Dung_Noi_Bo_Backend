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
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.account.BootstrapAdmin;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.requisition.RequisitionRecruiterRole;
import vn.ttcs.recruitment.requisition.RequisitionStatus;

import javax.sql.DataSource;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.ttcs.recruitment.auth.ApprovalTestSupport.step;

// Task 290: what a status change does when it has to wait for a lock, and when it races the other writers of the
// requisition. Every writer locks the requisition row, and waiters on a row lock are served in arrival order, so a
// race is staged by holding the row, sending the first request, then the second, and releasing the row.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RequisitionStatusChangeLockIntegrationTest {
    private static final Instant START = Instant.parse("2026-10-11T03:00:00Z");
    private static final String HOLD_REQUISITION =
            "SELECT pg_backend_pid() FROM recruitment_requisitions WHERE id = ? FOR SHARE";

    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private AccountRepository accounts;
    @Autowired private BootstrapAdmin bootstrap;
    @Autowired private AuthIntegrationTest.MutableClock clock;
    @Autowired private PlatformTransactionManager transactions;

    private RequisitionStatusTestSupport status;
    private JobPostingTestSupport postings;
    private ApprovalTestSupport api;
    private List<Map<String, Object>> permissionSeed;
    private String passwordHash;
    private UUID adminId;
    private UUID hrId;
    private String hrToken;
    private String hr2Token;
    private String itHeadToken;
    private UUID recruiterId;
    private String recruiterToken;
    private String approverToken;
    private UUID it;
    private UUID ops;
    private UUID position;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        String key = Base64.getEncoder().encodeToString(secret);
        registry.add("app.auth.jwt-secret", () -> key);
    }

    @BeforeEach
    void resetFixture() throws Exception {
        permissionSeed = jdbc.queryForList("SELECT role_code, permission_code FROM role_permissions");
        clock.set(START);
        status = new RequisitionStatusTestSupport(jdbc, json, transactions,
                Integer.parseInt(environment.getRequiredProperty("local.server.port")));
        postings = status.postings;
        api = status.api;
        status.reset();
        bootstrap.run(new DefaultApplicationArguments());
        adminId = jdbc.queryForObject("SELECT id FROM user_accounts WHERE email = 'admin@example.test'", UUID.class);
        passwordHash = accounts.findById(adminId).orElseThrow().getPasswordHash();
        hrId = account("hr@example.test", Role.HR_MANAGER);
        account("hr2@example.test", Role.HR_MANAGER);
        UUID itHead = account("it@example.test", Role.HIRING_MANAGER);
        recruiterId = account("recruiter@example.test", Role.RECRUITER);
        UUID approverId = account("approver@example.test", Role.APPROVER);
        hrToken = api.token("hr@example.test");
        hr2Token = api.token("hr2@example.test");
        itHeadToken = api.token("it@example.test");
        recruiterToken = api.token("recruiter@example.test");
        approverToken = api.token("approver@example.test");
        // IT's only level is HR_MANAGER; OPS's only level is the approver, who manages no department (READ_SCOPED).
        it = api.department("IT", itHead, null, START);
        ops = api.department("OPS", itHead, null, START);
        api.flow(it, hrId, START, step(1, null, null, "HR_MANAGER"));
        api.flow(ops, hrId, START, step(1, null, approverId, null));
        position = api.position(START);
    }

    @AfterEach
    void restorePermissionSeed() {
        if (permissionSeed == null) { return; }
        jdbc.update("DELETE FROM role_permissions");
        jdbc.batchUpdate("INSERT INTO role_permissions (role_code, permission_code) VALUES (?, ?)", permissionSeed
                .stream().map(row -> new Object[] {row.get("role_code"), row.get("permission_code")}).toList());
    }

    // HR pauses an approved requisition while a save holds its row, or while an admin lock or a logout holds HR's
    // account. Whatever changed meanwhile is seen once the lock is granted: the token expiring, the permission to
    // change statuses lost or narrowed to a department (WRITE_SCOPED never changes a status), the permission to read
    // requisitions lost, the requisition cancelled by someone else.
    @ParameterizedTest(name = "{0} held, then {1}")
    @CsvSource({
            "requisition, nothing, 200", "requisition, token-expired, 401", "requisition, permission-lost, 403",
            "requisition, downgraded-to-scoped, 403", "requisition, read-lost, 403",
            "requisition, cancelled-meanwhile, 409",
            "actor, locked-actor, 401", "actor, revoked-session, 401"
    })
    void theCallerIsCheckedAgainAfterEachLockWait(String held, String change, int expected) throws Exception {
        UUID id = requisition(it, RequisitionStatus.APPROVED);
        boolean onRequisition = held.equals("requisition");
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blocker = onRequisition ? lock(connection, HOLD_REQUISITION, id)
                    : lock(connection, "SELECT pg_backend_pid() FROM user_accounts WHERE id = ? FOR SHARE", hrId);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(() -> status.change(id, "PAUSED", "Chờ ngân sách", 0, hrToken));
                awaitWaiters(blocker, 1);
                switch (change) {
                    case "token-expired" -> clock.set(START.plus(Duration.ofMinutes(16)));
                    case "permission-lost" -> jdbc.update("DELETE FROM role_permissions WHERE role_code = "
                            + "'HR_MANAGER' AND permission_code = 'REQUISITIONS_WRITE_ALL'");
                    case "downgraded-to-scoped" -> {
                        jdbc.update("DELETE FROM role_permissions WHERE role_code = 'HR_MANAGER' "
                                + "AND permission_code = 'REQUISITIONS_WRITE_ALL'");
                        jdbc.update("INSERT INTO role_permissions (role_code, permission_code) "
                                + "VALUES ('HR_MANAGER', 'REQUISITIONS_WRITE_SCOPED') ON CONFLICT DO NOTHING");
                    }
                    case "read-lost" -> jdbc.update("DELETE FROM role_permissions WHERE role_code = 'HR_MANAGER' "
                            + "AND permission_code = 'REQUISITIONS_READ_ALL'");
                    // Another user (ADMIN) cancels the requisition meanwhile and writes its history row. Naming HR
                    // would work too: the waiting request holds HR's account FOR NO KEY UPDATE, which the row's
                    // foreign key check (FOR KEY SHARE) does not wait for (see RequisitionStatusService).
                    case "cancelled-meanwhile" -> {
                        execute(connection, "UPDATE recruitment_requisitions SET status = 'CANCELLED' WHERE id = ?",
                                id);
                        execute(connection, """
                                INSERT INTO requisition_workflow_events (id, requisition_id, revision, event_type,
                                    status_before, status_after, actor_id, occurred_at, comment)
                                VALUES (?, ?, 1, 'CANCELLED', 'APPROVED', 'CANCELLED', ?, ?, 'Không còn nhu cầu')
                                """, UUID.randomUUID(), id, adminId, Timestamp.from(START));
                    }
                    case "locked-actor" -> execute(connection, "UPDATE user_accounts SET admin_locked_at = ?, "
                            + "admin_lock_reason = 'Review', admin_locked_by = ? WHERE id = ?",
                            Timestamp.from(START), adminId, hrId);
                    case "revoked-session" -> execute(connection,
                            "UPDATE auth_sessions SET revoked_at = ? WHERE user_id = ?", Timestamp.from(START), hrId);
                    default -> { }
                }
                connection.commit();
                var result = response.get(15, TimeUnit.SECONDS);
                assertThat(result.statusCode()).as(result.body()).isEqualTo(expected);
                if (expected == 401) {
                    assertThat(json.readTree(result.body()).path("code").asText()).isEqualTo("SESSION_INVALID");
                }
                if (expected == 409) {
                    assertThat(json.readTree(result.body()).path("code").asText())
                            .isEqualTo("REQUISITION_STATUS_CHANGE_NOT_ALLOWED");
                }
            }
        }
        boolean cancelled = change.equals("cancelled-meanwhile");
        assertThat(status.events(id)).hasSize(expected == 200 || cancelled ? 1 : 0);
        assertThat(api.status(id)).isEqualTo(expected == 200 ? "PAUSED" : cancelled ? "CANCELLED" : "APPROVED");
    }

    // The reason is checked before any lock, so a request without one is answered at once even while the
    // requisition row is held.
    @Test
    void aMissingReasonDoesNotWaitForTheLock() throws Exception {
        UUID id = requisition(it, RequisitionStatus.APPROVED);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            lock(connection, HOLD_REQUISITION, id);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(() -> status.change(id, "PAUSED", null, 0, hrToken))
                        .get(5, TimeUnit.SECONDS);
                assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
                assertThat(json.readTree(response.body()).path("code").asText())
                        .isEqualTo("REQUISITION_STATUS_REASON_REQUIRED");
            }
            connection.commit();
        }
        assertThat(status.events(id)).isEmpty();
    }

    // The last level approves, then HR cancels on the page it loaded before: the revision moved on.
    @Test
    void aDecisionThenACancelOnTheOldRevision() throws Exception {
        UUID id = api.submitted(position, it, 22_000_000L, itHeadToken, itHeadToken);
        var responses = staged(id, () -> api.decide(id, "APPROVE", null, 1, hrToken),
                () -> status.change(id, "CANCELLED", "Không còn nhu cầu", 1, hr2Token));
        assertSecond(responses, "REQUISITION_STATE_CHANGED");
        assertThat(api.status(id)).isEqualTo("APPROVED");
        assertThat(api.events(id)).isEqualTo(2);
    }

    // HR cancels, then the approver of the level that waited decides on its old page. The approver manages no
    // department: it still reads the requisition (task 293), so it is told the requisition no longer waits (409),
    // never 403.
    @Test
    void aCancelThenALateDecisionOfTheWaitingApprover() throws Exception {
        UUID id = api.submitted(position, ops, 22_000_000L, itHeadToken, itHeadToken);
        var responses = staged(id, () -> status.change(id, "CANCELLED", "Không còn nhu cầu", 1, hrToken),
                () -> api.decide(id, "APPROVE", null, 1, approverToken));
        assertSecond(responses, "REQUISITION_NOT_PENDING_APPROVAL");
        assertThat(api.status(id)).isEqualTo("CANCELLED");
        assertThat(api.events(id)).isEqualTo(2);
    }

    @Test
    void aSubmitThenACancelOnTheOldRevision() throws Exception {
        UUID id = api.draft(position, it, 22_000_000L, itHeadToken);
        var responses = staged(id, () -> api.request("POST", RequisitionStatusTestSupport.REQUISITIONS + id
                + "/submit", null, itHeadToken), () -> status.change(id, "CANCELLED", "Trùng yêu cầu", 0, hrToken));
        assertSecond(responses, "REQUISITION_STATE_CHANGED");
        assertThat(api.status(id)).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    void aCancelThenASubmit() throws Exception {
        UUID id = api.draft(position, it, 22_000_000L, itHeadToken);
        var responses = staged(id, () -> status.change(id, "CANCELLED", "Trùng yêu cầu", 0, hrToken),
                () -> api.request("POST", RequisitionStatusTestSupport.REQUISITIONS + id + "/submit", null,
                        itHeadToken));
        assertSecond(responses, "REQUISITION_NOT_SUBMITTABLE");
        assertThat(api.status(id)).isEqualTo("CANCELLED");
        assertThat(api.events(id)).isOne();
    }

    @Test
    void anAssignmentThenAFill() throws Exception {
        UUID id = requisition(it, RequisitionStatus.APPROVED);
        var responses = staged(id, () -> assign(id), () -> status.change(id, "FILLED", "Đã tuyển đủ", 0, hr2Token));
        assertSecond(responses, null);
        assertThat(api.status(id)).isEqualTo("FILLED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM requisition_recruiters WHERE requisition_id = ?",
                Integer.class, id)).isOne();
    }

    @Test
    void aFillThenAnAssignment() throws Exception {
        UUID id = requisition(it, RequisitionStatus.APPROVED);
        var responses = staged(id, () -> status.change(id, "FILLED", "Đã tuyển đủ", 0, hr2Token), () -> assign(id));
        assertSecond(responses, "REQUISITION_NOT_ASSIGNABLE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM requisition_recruiters WHERE requisition_id = ?",
                Integer.class, id)).isZero();
    }

    // A posting save locks the requisition first (task 303), so it sees the pause committed while it waited.
    @Test
    void aPauseThenAPostingSave() throws Exception {
        UUID id = requisition(it, RequisitionStatus.APPROVED);
        postings.assign(id, recruiterId, RequisitionRecruiterRole.PRIMARY, hrId, START);
        var created = postings.created(Map.of("requisitionId", id), recruiterToken);
        UUID posting = UUID.fromString(created.path("id").asText());
        Map<String, Object> change = postings.putBody(created);
        change.put("benefits", "Bảo hiểm đầy đủ.");
        var responses = staged(id, () -> status.change(id, "PAUSED", "Chờ ngân sách", 0, hrToken),
                () -> postings.update(posting, change, recruiterToken));
        assertSecond(responses, "JOB_POSTING_REQUISITION_NOT_APPROVED");
        assertThat(postings.row(posting).get("benefits")).isNull();
    }

    // Two HR managers cancel the same requisition at the same moment: one change is recorded, the other is told the
    // change is no longer possible.
    @Test
    void twoCancelsAtOnceRecordOne() throws Exception {
        UUID id = requisition(it, RequisitionStatus.APPROVED);
        var responses = concurrently(id,
                () -> status.change(id, "CANCELLED", "Không còn nhu cầu", 0, hrToken),
                () -> status.change(id, "CANCELLED", "Trùng yêu cầu", 0, hr2Token));
        assertThat(responses).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(200, 409);
        assertThat(responses.stream().filter(response -> response.statusCode() == 409)
                .map(response -> json.readTree(response.body()).path("code").asText()).toList())
                .containsExactly("REQUISITION_STATUS_CHANGE_NOT_ALLOWED");
        assertThat(status.events(id)).extracting(event -> event.get("event_type")).containsExactly("CANCELLED");
    }

    // A complete requisition of the department in this status, written with SQL by ADMIN, with no history.
    private UUID requisition(UUID department, RequisitionStatus requisitionStatus) {
        return postings.requisition(position, department, adminId, requisitionStatus, START);
    }

    private HttpResponse<String> assign(UUID requisition) throws Exception {
        return api.request("POST", RequisitionStatusTestSupport.REQUISITIONS + requisition + "/assign",
                json.writeValueAsString(Map.of("recruiterId", recruiterId)), hrToken);
    }

    // The first request succeeded; the second got 409 with this code, or succeeded too when the code is null.
    private void assertSecond(List<HttpResponse<String>> responses, String code) {
        assertThat(responses.getFirst().statusCode()).as(responses.getFirst().body()).isEqualTo(200);
        var second = responses.get(1);
        assertThat(second.statusCode()).as(second.body()).isEqualTo(code == null ? 200 : 409);
        if (code != null) {
            assertThat(json.readTree(second.body()).path("code").asText()).isEqualTo(code);
        }
    }

    // Holds the requisition row, sends first and waits until it waits for the row, then sends second and waits for
    // it too, and releases the row: first runs, then second.
    private List<HttpResponse<String>> staged(UUID requisition, Callable<HttpResponse<String>> first,
                                              Callable<HttpResponse<String>> second) throws Exception {
        List<HttpResponse<String>> results = new ArrayList<>();
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blocker = lock(connection, HOLD_REQUISITION, requisition);
            try (var executor = Executors.newFixedThreadPool(2)) {
                Future<HttpResponse<String>> firstResponse = executor.submit(first);
                awaitWaiters(blocker, 1);
                Future<HttpResponse<String>> secondResponse = executor.submit(second);
                awaitWaiters(blocker, 2);
                connection.commit();
                results.add(firstResponse.get(20, TimeUnit.SECONDS));
                results.add(secondResponse.get(20, TimeUnit.SECONDS));
            }
        }
        return results;
    }

    // Holds the requisition row so both requests reach it, then lets them run one after the other.
    @SafeVarargs
    private List<HttpResponse<String>> concurrently(UUID requisition, Callable<HttpResponse<String>>... calls)
            throws Exception {
        List<HttpResponse<String>> results = new ArrayList<>();
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blocker = lock(connection, HOLD_REQUISITION, requisition);
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

    private UUID account(String email, Role... roles) {
        return accounts.saveAndFlush(new Account(email, "Lock test", passwordHash, Set.of(roles), START)).getId();
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
