package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.application.ApplicationStorageService;
import vn.ttcs.recruitment.application.CvValidator;

import javax.sql.DataSource;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.SQLException;
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
 * Task 380 (story S5-03): the stage change's locks over real HTTP and PostgreSQL. The change locks the caller's account
 * and session, then the requisition FOR NO KEY UPDATE, then the application FOR NO KEY UPDATE (the order of the
 * candidate's withdrawal, ApplicationWithdrawalAccess), checks the caller again after each wait and changes the stage
 * only from the stage it locked, together with its history row. Waits are observed with pg_blocking_pids and NOWAIT
 * probes, never with sleeps; every await fails loudly after 1.5 s (inside the 2 s lock_timeout).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=false", "debug=false", "logging.level.root=WARN",
        "app.auth.jwt-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.application-confirmation.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.application-confirmation.page-url=https://portal.example.test/tra-cuu",
        "app.application-confirmation.poll-delay-ms=3600000", "app.application-intake.cleanup-delay-ms=3600000",
        "app.application-tracking.cleanup-delay-ms=3600000", "app.application-notice.poll-delay-ms=3600000",
        "spring.mail.host=127.0.0.1", "spring.mail.port=1"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApplicationStageChangeLockIntegrationTest {
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
    private UUID requisition;
    private UUID primary;
    private String primaryToken;
    private String hrToken;
    private ApplicationTestSupport.Stored lan;

    @BeforeEach
    void fixture() throws Exception {
        clock.set(ApplicationTestSupport.NOW);
        int port = Integer.parseInt(environment.getRequiredProperty("local.server.port"));
        applications = new ApplicationTestSupport(jdbc, json, storage, validator, port);
        applications.reset();
        new JobPostingTestSupport(jdbc, json, transactions, port).reset();
        UUID posting = applications.publishedPosting("Lập trình viên Java");
        requisition = applications.requisitionOf(posting);
        UUID hr = applications.account("Nguyễn Thị Hoa", "hr@example.test", Role.HR_MANAGER);
        primary = applications.account("Trần Văn Bình", "primary@example.test", Role.RECRUITER);
        applications.assign(requisition, primary, "PRIMARY", hr);
        primaryToken = applications.token(tokens, primary);
        // HR is on no team and manages no department: only its ALL grants let it write here.
        hrToken = applications.token(tokens, hr);
        lan = applications.stored(posting, EMAIL);
        // Warm-up of the HTTP path on another application, so the first measured request does not pay for it.
        var warmUp = applications.stored(posting, "warm-up@example.test");
        assertThat(change(warmUp.applicationId(), "SCREENING", "NEW").statusCode()).isEqualTo(200);
        jdbc.update("DELETE FROM application_stage_events");
    }

    // The downgrade tests narrow HR's grants; the seed is put back whatever happened.
    @AfterEach
    void restoreHrGrants() {
        jdbc.update("DELETE FROM role_permissions WHERE role_code = 'HR_MANAGER' "
                + "AND permission_code IN ('CANDIDATES_READ_SCOPED', 'CANDIDATES_WRITE_SCOPED')");
        jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('HR_MANAGER', "
                + "'CANDIDATES_READ_ALL'), ('HR_MANAGER', 'CANDIDATES_WRITE_ALL') ON CONFLICT DO NOTHING");
    }

    // HR (CANDIDATES_*_ALL, on no team, managing no department) waits behind the requisition or the application row;
    // meanwhile its role's ALL grant becomes the SCOPED one, so a permission of each kind remains. After the wait the
    // application is outside the narrowed scope: 403, nothing written. The scope is evaluated with the permissions
    // read after the wait, not with those of the check before it.
    @ParameterizedTest(name = "{0} held, {1} narrowed to {2}")
    @CsvSource({
            "requisition, CANDIDATES_WRITE_ALL, CANDIDATES_WRITE_SCOPED",
            "requisition, CANDIDATES_READ_ALL, CANDIDATES_READ_SCOPED",
            "application, CANDIDATES_WRITE_ALL, CANDIDATES_WRITE_SCOPED",
            "application, CANDIDATES_READ_ALL, CANDIDATES_READ_SCOPED"
    })
    void aGrantNarrowedToScopedDuringTheWaitIsSeen(String held, String all, String scoped) throws Exception {
        try (Connection blocker = dataSource.getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            if (held.equals("requisition")) {
                hold(blocker, "SELECT 1 FROM recruitment_requisitions WHERE id = ? FOR UPDATE", requisition);
            } else {
                hold(blocker, "SELECT 1 FROM job_applications WHERE id = ? FOR UPDATE", lan.applicationId());
            }
            Future<HttpResponse<String>> change;
            try {
                change = executor.submit(() -> change(lan.applicationId(), "SCREENING", "NEW", hrToken));
                applications.awaitWaiterOf(ApplicationTestSupport.backendPid(blocker));
                assertThat(jdbc.update("UPDATE role_permissions SET permission_code = ? "
                        + "WHERE role_code = 'HR_MANAGER' AND permission_code = ?", scoped, all)).isEqualTo(1);
            } finally {
                blocker.rollback();
            }
            assertThat(status(change)).isEqualTo(403);
        }
        assertThat(stageOf(lan.applicationId())).isEqualTo("NEW");
        assertThat(eventShapes()).isEmpty();
    }

    // The same request without the downgrade passes: HR's ALL grants are what let it write an application of a team it
    // is not on.
    @Test
    void anUnassignedHrManagerWritesThroughItsAllGrants() throws Exception {
        assertThat(change(lan.applicationId(), "SCREENING", "NEW", hrToken).statusCode()).isEqualTo(200);
        assertThat(eventShapes()).containsExactly("NEW>SCREENING STAFF");
    }

    // A change waits behind a requisition held by another transaction (here a closure written with SQL), and the
    // application row stays free meanwhile: the requisition comes first. Then it succeeds.
    @Test
    void aStageChangeWaitsForTheRequisitionAndThenSucceeds() throws Exception {
        try (Connection blocker = dataSource.getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try (var update = blocker.prepareStatement(
                    "UPDATE recruitment_requisitions SET status = 'FILLED' WHERE id = ?")) {
                update.setObject(1, requisition);
                assertThat(update.executeUpdate()).isEqualTo(1);
            }
            var change = executor.submit(() -> change(lan.applicationId(), "SCREENING", "NEW"));
            applications.awaitWaiterOf(ApplicationTestSupport.backendPid(blocker));
            assertThat(probe("SELECT 1 FROM job_applications WHERE id = ? FOR NO KEY UPDATE NOWAIT",
                    lan.applicationId())).isEqualTo("free");
            blocker.commit();
            assertThat(status(change)).isEqualTo(200);
        }
        assertThat(stageOf(lan.applicationId())).isEqualTo("SCREENING");
        assertThat(eventShapes()).containsExactly("NEW>SCREENING STAFF");
    }

    // The candidate withdraws while the change waits behind the withdrawal on the requisition: the change then locks
    // the application as WITHDRAWN, not as the NEW the recruiter saw, and is refused as stale. Only the withdrawal
    // is recorded.
    @Test
    void aWithdrawalThatCommitsFirstMakesTheChangeStale() throws Exception {
        try (Connection blocker = dataSource.getConnection(); var executor = Executors.newFixedThreadPool(2)) {
            blocker.setAutoCommit(false);
            hold(blocker, "SELECT 1 FROM job_applications WHERE id = ? FOR UPDATE", lan.applicationId());
            Future<HttpResponse<String>> withdrawal;
            Future<HttpResponse<String>> change;
            try {
                withdrawal = executor.submit(() -> applications.withdraw(lan.trackingCode(), EMAIL, null));
                int withdrawer = applications.awaitWaiterOf(ApplicationTestSupport.backendPid(blocker));
                assertThat(probe("SELECT 1 FROM recruitment_requisitions WHERE id = ? FOR SHARE NOWAIT", requisition))
                        .isEqualTo(LOCKED);
                change = executor.submit(() -> change(lan.applicationId(), "SCREENING", "NEW"));
                applications.awaitWaiterOf(withdrawer);
            } finally {
                blocker.rollback();
            }
            assertThat(status(withdrawal)).isEqualTo(200);
            var refused = change.get(10, TimeUnit.SECONDS);
            assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
            assertThat(json.readTree(refused.body()).path("code").asString()).isEqualTo("APPLICATION_STAGE_CHANGED");
        }
        assertThat(stageOf(lan.applicationId())).isEqualTo("WITHDRAWN");
        assertThat(eventShapes()).containsExactly("NEW>WITHDRAWN CANDIDATE");
    }

    // The other order: the change holds the requisition, the withdrawal waits behind it and then withdraws from the
    // stage the change committed. Both are recorded, in order.
    @Test
    void aWithdrawalBehindAChangeWithdrawsFromTheNewStage() throws Exception {
        try (Connection blocker = dataSource.getConnection(); var executor = Executors.newFixedThreadPool(2)) {
            blocker.setAutoCommit(false);
            hold(blocker, "SELECT 1 FROM job_applications WHERE id = ? FOR UPDATE", lan.applicationId());
            Future<HttpResponse<String>> change;
            Future<HttpResponse<String>> withdrawal;
            try {
                change = executor.submit(() -> change(lan.applicationId(), "SCREENING", "NEW"));
                int changer = applications.awaitWaiterOf(ApplicationTestSupport.backendPid(blocker));
                withdrawal = executor.submit(() -> applications.withdraw(lan.trackingCode(), EMAIL, null));
                applications.awaitWaiterOf(changer);
            } finally {
                blocker.rollback();
            }
            assertThat(status(change)).isEqualTo(200);
            assertThat(status(withdrawal)).isEqualTo(200);
        }
        assertThat(stageOf(lan.applicationId())).isEqualTo("WITHDRAWN");
        assertThat(eventShapes()).containsExactly("NEW>SCREENING STAFF", "SCREENING>WITHDRAWN CANDIDATE");
    }

    // A double drop of one card (or two recruiters at once): one change wins, the other sees the stage it committed
    // and is refused as stale. One history row.
    @Test
    void twoParallelIdenticalChangesWriteOneEvent() throws Exception {
        var barrier = new CyclicBarrier(2);
        List<Integer> statuses = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<HttpResponse<String>>> calls = new ArrayList<>();
            for (int call = 0; call < 2; call++) {
                calls.add(executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return change(lan.applicationId(), "SCREENING", "NEW");
                }));
            }
            for (var call : calls) statuses.add(call.get(20, TimeUnit.SECONDS).statusCode());
        }
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(eventShapes()).containsExactly("NEW>SCREENING STAFF");
    }

    // The stage and its history row commit together: when the row cannot be written (a test trigger refuses it), the
    // stage stays as it was.
    @Test
    void aHistoryRowThatFailsKeepsTheStage() throws Exception {
        jdbc.execute("""
                CREATE FUNCTION test_refuse_stage_event() RETURNS TRIGGER LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'refused by the test' USING ERRCODE = '23514'; END $$
                """);
        jdbc.execute("CREATE TRIGGER test_refuse_stage_event BEFORE INSERT ON application_stage_events "
                + "FOR EACH ROW EXECUTE FUNCTION test_refuse_stage_event()");
        try {
            var response = change(lan.applicationId(), "SCREENING", "NEW");
            assertThat(response.statusCode()).as(response.body()).isEqualTo(500);
        } finally {
            jdbc.execute("DROP TRIGGER test_refuse_stage_event ON application_stage_events");
            jdbc.execute("DROP FUNCTION test_refuse_stage_event()");
        }
        assertThat(stageOf(lan.applicationId())).isEqualTo("NEW");
        assertThat(eventShapes()).isEmpty();
    }

    // A handover committed while the change waits on the requisition is seen under the lock: 403, nothing written.
    @Test
    void aHandoverCommittedDuringTheWaitIsSeen() throws Exception {
        try (Connection blocker = dataSource.getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            hold(blocker, "SELECT 1 FROM recruitment_requisitions WHERE id = ? FOR UPDATE", requisition);
            var change = executor.submit(() -> change(lan.applicationId(), "SCREENING", "NEW"));
            applications.awaitWaiterOf(ApplicationTestSupport.backendPid(blocker));
            try (var delete = blocker.prepareStatement(
                    "DELETE FROM requisition_recruiters WHERE requisition_id = ? AND recruiter_id = ?")) {
                delete.setObject(1, requisition);
                delete.setObject(2, primary);
                assertThat(delete.executeUpdate()).isEqualTo(1);
            }
            blocker.commit();
            assertThat(status(change)).isEqualTo(403);
        }
        assertThat(stageOf(lan.applicationId())).isEqualTo("NEW");
        assertThat(eventShapes()).isEmpty();
    }

    // A grant removed from the role while the change waits is seen after the wait: 403, nothing written.
    @Test
    void aPermissionRemovedDuringTheWaitIsSeen() throws Exception {
        try (Connection blocker = dataSource.getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            hold(blocker, "SELECT 1 FROM recruitment_requisitions WHERE id = ? FOR UPDATE", requisition);
            Future<HttpResponse<String>> change;
            try {
                change = executor.submit(() -> change(lan.applicationId(), "SCREENING", "NEW"));
                applications.awaitWaiterOf(ApplicationTestSupport.backendPid(blocker));
                jdbc.update("DELETE FROM role_permissions WHERE role_code = 'RECRUITER' "
                        + "AND permission_code = 'CANDIDATES_WRITE_SCOPED'");
            } finally {
                blocker.rollback();
            }
            try {
                assertThat(status(change)).isEqualTo(403);
            } finally {
                jdbc.update("INSERT INTO role_permissions (role_code, permission_code) "
                        + "VALUES ('RECRUITER', 'CANDIDATES_WRITE_SCOPED')");
            }
        }
        assertThat(stageOf(lan.applicationId())).isEqualTo("NEW");
        assertThat(eventShapes()).isEmpty();
    }

    // A requisition held longer than lock_timeout (2 s): 503 APPLICATION_STAGE_BUSY, nothing kept; the same request
    // succeeds once the lock is free.
    @Test
    void aLockHeldTooLongAnswers503() throws Exception {
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            hold(blocker, "SELECT 1 FROM recruitment_requisitions WHERE id = ? FOR UPDATE", requisition);
            try {
                var response = change(lan.applicationId(), "SCREENING", "NEW");
                assertThat(response.statusCode()).as(response.body()).isEqualTo(503);
                assertThat(json.readTree(response.body()).path("code").asString()).isEqualTo("APPLICATION_STAGE_BUSY");
            } finally {
                blocker.rollback();
            }
        }
        assertThat(stageOf(lan.applicationId())).isEqualTo("NEW");
        assertThat(eventShapes()).isEmpty();
        assertThat(change(lan.applicationId(), "SCREENING", "NEW").statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> change(UUID application, String target, String expected) throws Exception {
        return change(application, target, expected, primaryToken);
    }

    private HttpResponse<String> change(UUID application, String target, String expected, String token)
            throws Exception {
        return applications.post("/api/v1/applications/" + application + "/stage-changes",
                "{\"targetStage\":\"" + target + "\",\"expectedCurrentStage\":\"" + expected + "\"}",
                "application/json", Map.of("Authorization", "Bearer " + token));
    }

    private static int status(Future<HttpResponse<String>> call) throws Exception {
        var response = call.get(10, TimeUnit.SECONDS);
        assertThat(response.statusCode()).as(response.body()).isIn(200, 403, 409, 503);
        return response.statusCode();
    }

    private static void hold(Connection connection, String sql, UUID id) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            statement.executeQuery().close();
        }
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

    // Every history row of Lan's application as "BEFORE>AFTER ACTOR", oldest first. The test clock stands still, so
    // two rows of one test share their time; the stage before then orders them (NEW comes before SCREENING).
    private List<String> eventShapes() {
        return jdbc.queryForList("""
                SELECT stage_before || '>' || stage_after || ' ' || actor_type FROM application_stage_events
                WHERE application_id = ? ORDER BY occurred_at, stage_before
                """, String.class, lan.applicationId());
    }
}
