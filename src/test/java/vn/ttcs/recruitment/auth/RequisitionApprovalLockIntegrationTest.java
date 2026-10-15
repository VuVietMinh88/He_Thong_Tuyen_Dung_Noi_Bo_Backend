package vn.ttcs.recruitment.auth;

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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.ttcs.recruitment.auth.ApprovalTestSupport.step;

// Task 262: what a decision does when it has to wait for a lock, and when two approvers decide at once.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RequisitionApprovalLockIntegrationTest {
    private static final Instant START = Instant.parse("2026-10-11T03:00:00Z");

    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private AccountRepository accounts;
    @Autowired private BootstrapAdmin bootstrap;
    @Autowired private AuthIntegrationTest.MutableClock clock;
    @Autowired private PlatformTransactionManager transactions;

    private ApprovalTestSupport api;
    private String passwordHash;
    private UUID adminId;
    private UUID hrId;
    private UUID hr2Id;
    private String hr2Token;
    private String hr3Token;
    private String itHeadToken;
    private UUID parent;
    private UUID it;
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
        clock.set(START);
        api = new ApprovalTestSupport(jdbc, json, transactions,
                Integer.parseInt(environment.getRequiredProperty("local.server.port")));
        jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('HR_MANAGER', 'REQUISITIONS_WRITE_ALL') ON CONFLICT DO NOTHING");
        jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('HR_MANAGER', 'REQUISITIONS_READ_ALL') ON CONFLICT DO NOTHING");
        jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('APPROVER', 'REQUISITIONS_WRITE_SCOPED') ON CONFLICT DO NOTHING");
        api.reset();
        bootstrap.run(new DefaultApplicationArguments());
        adminId = jdbc.queryForObject("SELECT id FROM user_accounts WHERE email = 'admin@example.test'", UUID.class);
        passwordHash = accounts.findById(adminId).orElseThrow().getPasswordHash();
        hrId = account("hr@example.test", Role.HR_MANAGER);
        hr2Id = account("hr2@example.test", Role.HR_MANAGER);
        account("hr3@example.test", Role.HR_MANAGER);
        UUID parentHead = account("parent@example.test", Role.HIRING_MANAGER);
        UUID itHead = account("it@example.test", Role.HIRING_MANAGER);
        hr2Token = api.token("hr2@example.test");
        hr3Token = api.token("hr3@example.test");
        itHeadToken = api.token("it@example.test");
        parent = api.department("PARENT", parentHead, null, START);
        it = api.department("IT", itHead, parent, START);
        position = api.position(START);
    }

    // The decision waits for the requisition row (a save holds it), for its department row (a department change
    // holds it) or for the decider's own account (an admin lock or a logout holds it). Whatever changed meanwhile is
    // checked once the lock is granted: losing the write permission (an HR manager, or an approver whose role stays
    // company-wide) or the read permission refuses the decision.
    @ParameterizedTest(name = "{0} held, then {1}")
    @CsvSource({
            "requisition, nothing, 200", "requisition, token-expired, 401", "requisition, permission-lost, 403",
            "requisition, approver-permission-lost, 403", "requisition, read-lost, 403",
            "department, nothing, 200", "department, token-expired, 401", "department, permission-lost, 403",
            "department, approver-permission-lost, 403", "department, read-lost, 403",
            "actor, locked-actor, 401", "actor, revoked-session, 401"
    })
    void theDeciderIsCheckedAgainAfterEachLockWait(String held, String change, int expected) throws Exception {
        boolean approverLevel = change.equals("approver-permission-lost");
        api.flow(it, hrId, START, step(1, null, null, approverLevel ? "APPROVER" : "HR_MANAGER"));
        if (approverLevel) {
            account("approver@example.test", Role.APPROVER);
        }
        String deciderToken = approverLevel ? api.token("approver@example.test") : hr2Token;
        UUID id = api.submitted(position, it, 22_000_000L, itHeadToken, itHeadToken);
        String sql = switch (held) {
            case "requisition" -> "SELECT pg_backend_pid() FROM recruitment_requisitions WHERE id = ? FOR SHARE";
            case "department" -> "SELECT pg_backend_pid() FROM departments WHERE id = ? FOR UPDATE";
            default -> "SELECT pg_backend_pid() FROM user_accounts WHERE id = ? FOR SHARE";
        };
        UUID heldId = switch (held) {
            case "requisition" -> id;
            case "department" -> it;
            default -> hr2Id;
        };
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blocker = lock(connection, sql, heldId);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(() -> api.decide(id, "REJECT", "Không phù hợp", 1, deciderToken));
                awaitWaiters(blocker, 1);
                switch (change) {
                    case "token-expired" -> clock.set(START.plus(Duration.ofMinutes(16)));
                    case "permission-lost" -> jdbc.update("DELETE FROM role_permissions WHERE role_code = 'HR_MANAGER' "
                            + "AND permission_code = 'REQUISITIONS_WRITE_ALL'");
                    case "approver-permission-lost" -> jdbc.update("DELETE FROM role_permissions WHERE role_code = "
                            + "'APPROVER' AND permission_code = 'REQUISITIONS_WRITE_SCOPED'");
                    case "read-lost" -> jdbc.update("DELETE FROM role_permissions WHERE role_code = 'HR_MANAGER' "
                            + "AND permission_code = 'REQUISITIONS_READ_ALL'");
                    case "locked-actor" -> execute(connection, "UPDATE user_accounts SET admin_locked_at = ?, "
                            + "admin_lock_reason = 'Review', admin_locked_by = ? WHERE id = ?",
                            Timestamp.from(START), adminId, hr2Id);
                    case "revoked-session" -> execute(connection,
                            "UPDATE auth_sessions SET revoked_at = ? WHERE user_id = ?", Timestamp.from(START), hr2Id);
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
        assertThat(api.events(id)).isEqualTo(expected == 200 ? 2 : 1);
        assertThat(api.status(id)).isEqualTo(expected == 200 ? "REJECTED" : "PENDING_APPROVAL");
    }

    // An admin removes the decider's role while the decision waits for the decider's account: the decision then sees
    // the account without the role.
    @Test
    void aRoleRemovedWhileTheDecisionWaitsIsSeen() throws Exception {
        api.flow(it, hrId, START, step(1, null, null, "APPROVER"));
        UUID approver = account("approver@example.test", Role.APPROVER, Role.HIRING_MANAGER);
        String approverToken = api.token("approver@example.test");
        UUID id = api.submitted(position, it, 22_000_000L, itHeadToken, itHeadToken);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blocker = lock(connection, "SELECT pg_backend_pid() FROM user_accounts WHERE id = ? FOR NO KEY UPDATE",
                    approver);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(() -> api.decide(id, "REJECT", "Không", 1, approverToken));
                awaitWaiters(blocker, 1);
                execute(connection, "DELETE FROM user_roles WHERE user_id = ? AND role = 'APPROVER'", approver);
                connection.commit();
                api.forbidden(response.get(15, TimeUnit.SECONDS));
            }
        }
        assertThat(api.events(id)).isOne();
    }

    // HR moves IT out from under PARENT while the decision of PARENT's head waits for IT's row: the head of PARENT no
    // longer manages IT and may not decide its HIRING_MANAGER level.
    @Test
    void aDepartmentChangeCommittedWhileTheDecisionWaitsIsSeen() throws Exception {
        api.flow(it, hrId, START, step(1, null, null, "HIRING_MANAGER"));
        String parentHeadToken = api.token("parent@example.test");
        UUID id = api.submitted(position, it, 22_000_000L, itHeadToken, itHeadToken);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blocker = lock(connection, "SELECT pg_backend_pid() FROM departments WHERE id = ? FOR UPDATE", it);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(() -> api.decide(id, "REJECT", "Không", 1, parentHeadToken));
                awaitWaiters(blocker, 1);
                execute(connection, "UPDATE departments SET parent_id = NULL WHERE id = ?", it);
                connection.commit();
                api.forbidden(response.get(15, TimeUnit.SECONDS));
            }
        }
        assertThat(api.events(id)).isOne();
    }

    // Two HR managers reject the same level at the same moment: one decision is recorded, the other is told the
    // requisition no longer waits.
    @Test
    void twoDecisionsAtOnceRecordOne() throws Exception {
        api.flow(it, hrId, START, step(1, null, null, "HR_MANAGER"));
        UUID id = api.submitted(position, it, 22_000_000L, itHeadToken, itHeadToken);

        var responses = concurrently(id,
                () -> api.decide(id, "REJECT", "Không phù hợp", 1, hr2Token),
                () -> api.decide(id, "REJECT", "Chưa cần", 1, hr3Token));
        assertThat(responses).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(200, 409);
        assertThat(responses.stream().filter(response -> response.statusCode() == 409)
                .map(response -> json.readTree(response.body()).path("code").asText()).toList())
                .containsExactly("REQUISITION_NOT_PENDING_APPROVAL");
        assertThat(api.events(id)).isEqualTo(2);
    }

    // Holds the requisition row so both requests reach it, then lets them run one after the other.
    @SafeVarargs
    private List<HttpResponse<String>> concurrently(UUID requisition, Callable<HttpResponse<String>>... calls)
            throws Exception {
        List<HttpResponse<String>> results = new ArrayList<>();
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blocker = lock(connection, "SELECT pg_backend_pid() FROM recruitment_requisitions WHERE id = ? FOR SHARE",
                    requisition);
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
