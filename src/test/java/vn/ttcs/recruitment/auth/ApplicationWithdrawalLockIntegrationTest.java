package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.application.ApplicationStorageService;
import vn.ttcs.recruitment.application.CvValidator;

import javax.sql.DataSource;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 345 (story S4-06): the withdrawal's locks over real HTTP and PostgreSQL. The withdrawal locks the requisition
 * FOR NO KEY UPDATE, then the application FOR NO KEY UPDATE (ApplicationWithdrawalAccess, task 344), and changes the
 * stage only from the stage it locked. Lock waits are observed with pg_blocking_pids and NOWAIT probes from other
 * connections, never with sleeps; every await fails loudly after 1.5 s (inside the 2 s lock_timeout).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=false", "debug=false", "logging.level.root=WARN",
        "app.auth.jwt-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.application-confirmation.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.application-confirmation.page-url=https://portal.example.test/tra-cuu",
        "app.application-confirmation.poll-delay-ms=3600000", "app.application-intake.cleanup-delay-ms=3600000",
        "app.application-tracking.cleanup-delay-ms=3600000",
        "spring.mail.host=127.0.0.1", "spring.mail.port=1"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApplicationWithdrawalLockIntegrationTest {
    private static final String EMAIL = "lan@example.test";
    private static final String LOCKED = "55P03";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private Environment environment;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ApplicationStorageService storage;
    @Autowired private CvValidator validator;
    @Autowired private AuthIntegrationTest.MutableClock clock;
    @Autowired private TokenService tokens;

    private ApplicationTestSupport applications;
    private UUID posting;
    private UUID requisition;

    @BeforeEach
    void fixture() {
        clock.set(ApplicationTestSupport.NOW);
        int port = Integer.parseInt(environment.getRequiredProperty("local.server.port"));
        applications = new ApplicationTestSupport(jdbc, json, storage, validator, port);
        applications.reset();
        new JobPostingTestSupport(jdbc, json, transactions, port).reset();
        posting = applications.publishedPosting("Lập trình viên Java");
        requisition = applications.requisitionOf(posting);
    }

    // Two withdrawals of one application at the same moment: both answer 200 with the same withdrawnAt, and exactly
    // one event is written; the second runs after the first on the requisition lock and is a repeat.
    @Test
    void twoParallelWithdrawalsWriteOneEventAndAnswerAlike() throws Exception {
        var stored = applications.stored(posting, EMAIL);
        ok(applications.lookup(stored.trackingCode(), EMAIL));          // warm-up of the HTTP path
        var barrier = new CyclicBarrier(2);
        List<String> bodies = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<HttpResponse<String>>> calls = new ArrayList<>();
            for (int call = 0; call < 2; call++) {
                calls.add(executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return applications.withdraw(stored.trackingCode(), EMAIL, "Lý do");
                }));
            }
            for (var call : calls) bodies.add(ok(call.get(20, TimeUnit.SECONDS)).toString());
        }
        assertThat(bodies.get(0)).isEqualTo(bodies.get(1));
        assertThat(eventCount()).isEqualTo(1);
        assertThat(stageOf(stored.applicationId())).isEqualTo("WITHDRAWN");
    }

    // A withdrawal waits behind a requisition held by another transaction (here a closure written with SQL), then
    // succeeds once it commits: the requisition's status does not matter, only its lock.
    @Test
    void aWithdrawalWaitsForTheRequisitionAndThenSucceeds() throws Exception {
        var stored = applications.stored(posting, EMAIL);
        ok(applications.lookup(stored.trackingCode(), EMAIL));          // warm-up of the HTTP path
        try (Connection blocker = dataSource.getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try (var update = blocker.prepareStatement(
                    "UPDATE recruitment_requisitions SET status = 'FILLED' WHERE id = ?")) {
                update.setObject(1, requisition);
                assertThat(update.executeUpdate()).isEqualTo(1);
            }
            var withdrawal = executor.submit(() -> applications.withdraw(stored.trackingCode(), EMAIL, null));
            applications.awaitWaiterOf(ApplicationTestSupport.backendPid(blocker));
            // The withdrawal waits at the requisition: the application row is still free.
            assertThat(probe("SELECT 1 FROM job_applications WHERE id = ? FOR NO KEY UPDATE NOWAIT",
                    stored.applicationId())).isEqualTo("free");
            blocker.commit();
            ok(withdrawal.get(10, TimeUnit.SECONDS));
        }
        assertThat(stageOf(stored.applicationId())).isEqualTo("WITHDRAWN");
        assertThat(eventCount()).isEqualTo(1);
    }

    // A closure behind a withdrawal. A raw connection holds the application row; the withdrawal takes the requisition
    // FOR NO KEY UPDATE and waits on that row; a share lock on the requisition is refused at once (NOWAIT), so the
    // withdrawal holds it; a closure (warmed up on another requisition) queues behind the withdrawal's backend. Once the
    // row is free, the withdrawal answers 200, then the closure counts no active application and needs no confirmation.
    @Test
    void aClosureWaitsForTheWithdrawalAndThenCountsNoActiveApplication() throws Exception {
        String hr = token(account("Nguyễn Thị Hoa", "hr@example.test", Role.HR_MANAGER));
        UUID warmUp = applications.requisitionOf(applications.publishedPosting("Kế toán"));
        assertThat(close(warmUp, hr).statusCode()).isEqualTo(200);
        var stored = applications.stored(posting, EMAIL);
        ok(applications.lookup(stored.trackingCode(), EMAIL));          // warm-up of the HTTP path

        try (Connection blocker = dataSource.getConnection(); var executor = Executors.newFixedThreadPool(2)) {
            blocker.setAutoCommit(false);
            try (var hold = blocker.prepareStatement("SELECT 1 FROM job_applications WHERE id = ? FOR UPDATE")) {
                hold.setObject(1, stored.applicationId());
                hold.executeQuery().close();
            }
            Future<HttpResponse<String>> withdrawal;
            Future<HttpResponse<String>> closure;
            try {
                withdrawal = executor.submit(() -> applications.withdraw(stored.trackingCode(), EMAIL, null));
                int withdrawer = applications.awaitWaiterOf(ApplicationTestSupport.backendPid(blocker));
                assertThat(probe("SELECT 1 FROM recruitment_requisitions WHERE id = ? FOR SHARE NOWAIT", requisition))
                        .isEqualTo(LOCKED);
                closure = executor.submit(() -> close(requisition, hr));
                int closer = applications.awaitWaiterOf(withdrawer);
                assertThat(closer).isNotEqualTo(ApplicationTestSupport.backendPid(blocker));
            } finally {
                blocker.rollback();
            }
            ok(withdrawal.get(10, TimeUnit.SECONDS));
            var closed = closure.get(10, TimeUnit.SECONDS);
            assertThat(closed.statusCode()).as(closed.body()).isEqualTo(200);
        }
        assertThat(stageOf(stored.applicationId())).isEqualTo("WITHDRAWN");
        assertThat(jdbc.queryForObject("SELECT status FROM recruitment_requisitions WHERE id = ?", String.class,
                requisition)).isEqualTo("FILLED");
    }

    // A code revoked while the withdrawal waits on the requisition is seen on the locked row: 404, nothing written.
    @Test
    void aRevocationCommittedDuringTheWaitAnswers404() throws Exception {
        var stored = applications.stored(posting, EMAIL);
        ok(applications.lookup(stored.trackingCode(), EMAIL));          // warm-up of the HTTP path
        try (Connection blocker = dataSource.getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try (var hold = blocker.prepareStatement("SELECT 1 FROM recruitment_requisitions WHERE id = ? FOR UPDATE")) {
                hold.setObject(1, requisition);
                hold.executeQuery().close();
            }
            Future<HttpResponse<String>> withdrawal;
            try {
                withdrawal = executor.submit(() -> applications.withdraw(stored.trackingCode(), EMAIL, null));
                applications.awaitWaiterOf(ApplicationTestSupport.backendPid(blocker));
                assertThat(jdbc.update("UPDATE job_applications SET tracking_revoked_at = clock_timestamp() WHERE id = ?",
                        stored.applicationId())).isEqualTo(1);
            } finally {
                blocker.rollback();
            }
            var response = withdrawal.get(10, TimeUnit.SECONDS);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(404);
            assertThat(json.readTree(response.body()).get("code").asString()).isEqualTo("APPLICATION_LOOKUP_NOT_FOUND");
        }
        assertThat(stageOf(stored.applicationId())).isEqualTo("NEW");
        assertThat(eventCount()).isZero();
    }

    private JsonNode ok(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private HttpResponse<String> close(UUID id, String token) throws Exception {
        return applications.post("/api/v1/requisitions/" + id + "/status-changes",
                "{\"status\":\"FILLED\",\"reason\":\"Đã tuyển đủ\",\"expectedRevision\":0}", "application/json",
                Map.of("Authorization", "Bearer " + token));
    }

    // "free" when another connection takes the lock at once, the SQLSTATE when it is refused (55P03: lock held).
    private String probe(String sql, UUID id) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement(sql)) {
                statement.setObject(1, id);
                statement.executeQuery().close();
                return "free";
            } catch (SQLException exception) {
                return exception.getSQLState();
            } finally {
                connection.rollback();
            }
        }
    }

    private String stageOf(UUID application) {
        return jdbc.queryForObject("SELECT stage FROM job_applications WHERE id = ?", String.class, application);
    }

    private int eventCount() {
        return jdbc.queryForObject("SELECT count(*) FROM application_stage_events", Integer.class);
    }

    private String token(UUID account) {
        UUID session = UUID.randomUUID();
        jdbc.update("INSERT INTO auth_sessions (id, user_id, refresh_token_hash, created_at, expires_at)"
                + " VALUES (?, ?, ?, ?, ?)",
                session, account, (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", ""),
                Timestamp.from(ApplicationTestSupport.NOW.minusSeconds(60)),
                Timestamp.from(ApplicationTestSupport.NOW.plus(Duration.ofDays(7))));
        return tokens.createAccessToken(account, session);
    }

    private UUID account(String fullName, String email, Role... roles) {
        UUID id = applications.staff(fullName, email);
        for (Role role : roles) {
            jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, ?)", id, role.name());
        }
        return id;
    }
}
