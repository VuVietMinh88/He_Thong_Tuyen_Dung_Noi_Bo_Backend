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
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.application.ApplicationStorageService;
import vn.ttcs.recruitment.application.CvValidator;

import javax.sql.DataSource;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 351 (story S4-07): the referral's locks over real HTTP and PostgreSQL. The writer locks the caller's account,
 * then its session, then the requisition FOR NO KEY UPDATE (the 321 rule), and checks the caller again after every
 * wait through the shared posting gate; storage locks the posting FOR SHARE. Lock waits are observed with pg_blocking_pids and
 * NOWAIT probes from other connections, never with sleeps; every await fails loudly after 1.5 s (inside the 2 s
 * lock_timeout), and the HTTP paths are warmed up first. One referral is read at a time here
 * (app.referral.max-concurrent=1), so a second one meets the busy gate. Two referrals are also checked by
 * ApplicationReferralIntegrationTest.twoReferrersBehindASharedRowLockBothSucceed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=false", "debug=false", "logging.level.root=WARN",
        "app.auth.jwt-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.application-confirmation.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.application-confirmation.page-url=https://portal.example.test/tra-cuu",
        "app.application-confirmation.poll-delay-ms=3600000", "app.application-intake.cleanup-delay-ms=3600000",
        "app.application-tracking.cleanup-delay-ms=3600000", "app.application-notice.poll-delay-ms=3600000",
        "app.referral.max-concurrent=1", "spring.mail.host=127.0.0.1", "spring.mail.port=1"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApplicationReferralLockIntegrationTest {
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
    private UUID referrer;
    private String token;

    @BeforeEach
    void fixture() throws Exception {
        clock.set(ApplicationTestSupport.NOW);
        int port = Integer.parseInt(environment.getRequiredProperty("local.server.port"));
        applications = new ApplicationTestSupport(jdbc, json, storage, validator, port);
        applications.reset();
        new JobPostingTestSupport(jdbc, json, transactions, port).reset();
        posting = applications.publishedPosting("Lập trình viên Java");
        requisition = applications.requisitionOf(posting);
        referrer = applications.account("Trần Văn Nam", "nam@staff.test", Role.RECRUITER);
        token = applications.token(tokens, referrer);
        // Warm-up of the referral path on another posting, so a timed wait below measures the lock, not class loading.
        status(applications.refer(token, applications.publishedPosting("Khởi động"), "warm@example.test"), 201);
        jdbc.update("DELETE FROM referral_attempts");
    }

    // A transaction that holds the requisition (here a closure written with SQL) makes the referral wait at the
    // requisition, before the posting; once it commits FILLED, the referral sees it under its own lock: 404, nothing
    // stored, the attempt POSTING_UNAVAILABLE.
    @Test
    void aReferralWaitsForTheRequisitionAndThenSeesItFilled() throws Exception {
        try (Connection blocker = dataSource.getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try (var update = blocker.prepareStatement("UPDATE recruitment_requisitions SET status = 'FILLED' WHERE id = ?")) {
                update.setObject(1, requisition);
                assertThat(update.executeUpdate()).isEqualTo(1);
            }
            Future<HttpResponse<String>> referral;
            try {
                referral = executor.submit(() -> applications.refer(token, posting, EMAIL));
                applications.awaitWaiterOf(ApplicationTestSupport.backendPid(blocker));
                // It waits at the requisition: the posting row is still free.
                assertThat(probe("SELECT 1 FROM job_postings WHERE id = ? FOR UPDATE NOWAIT", posting)).isEqualTo("free");
                blocker.commit();
            } finally {
                blocker.rollback();         // a no-op after the commit; frees the referral if an await failed
            }
            var response = referral.get(10, TimeUnit.SECONDS);
            status(response, 404);
            assertThat(json.readTree(response.body()).get("code").asString()).isEqualTo("JOB_POSTING_NOT_FOUND");
        }
        assertThat(applicationsOf(posting)).isZero();
        assertThat(outcomes()).containsExactly("POSTING_UNAVAILABLE");
    }

    // A closure behind a referral. A raw connection holds the posting row; the referral takes the account, the session
    // and the requisition FOR NO KEY UPDATE, then waits in store() on that row; a share lock on the requisition is
    // refused at once (NOWAIT), proving the gate's FOR NO KEY UPDATE; a closure (warmed up on another requisition)
    // queues behind the referral's backend. Once the row is free,
    // the referral answers 201, then the closure counts the new application and asks for confirmation (409), so no
    // application is closed unseen.
    @Test
    void aClosureWaitsForTheReferralAndThenCountsIt() throws Exception {
        String hr = applications.token(tokens, applications.account("Nguyễn Thị Hoa", "hr@staff.test", Role.HR_MANAGER));
        UUID warmUp = applications.requisitionOf(applications.publishedPosting("Kế toán"));
        status(close(warmUp, hr), 200);

        try (Connection blocker = dataSource.getConnection(); var executor = Executors.newFixedThreadPool(2)) {
            blocker.setAutoCommit(false);
            try (var hold = blocker.prepareStatement("SELECT 1 FROM job_postings WHERE id = ? FOR UPDATE")) {
                hold.setObject(1, posting);
                hold.executeQuery().close();
            }
            Future<HttpResponse<String>> referral;
            Future<HttpResponse<String>> closure;
            try {
                referral = executor.submit(() -> applications.refer(token, posting, EMAIL));
                int referring = applications.awaitWaiterOf(ApplicationTestSupport.backendPid(blocker));
                assertThat(probe("SELECT 1 FROM recruitment_requisitions WHERE id = ? FOR SHARE NOWAIT", requisition))
                        .isEqualTo(LOCKED);
                closure = executor.submit(() -> close(requisition, hr));
                int closer = applications.awaitWaiterOf(referring);
                assertThat(closer).isNotEqualTo(ApplicationTestSupport.backendPid(blocker));
            } finally {
                blocker.rollback();
            }
            status(referral.get(10, TimeUnit.SECONDS), 201);
            var closed = closure.get(10, TimeUnit.SECONDS);
            status(closed, 409);
            assertThat(json.readTree(closed.body()).get("code").asString()).isEqualTo("REQUISITION_HAS_ACTIVE_CANDIDATES");
        }
        assertThat(applicationsOf(posting)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM recruitment_requisitions WHERE id = ?", String.class,
                requisition)).isEqualTo("APPROVED");
        assertThat(outcomes()).containsExactly("STORED");
    }

    // An administrator lock committed while the referral waits on the caller's account row: the writer reads the
    // locked account under its own lock and answers 401; nothing is stored; the attempt is REFUSED.
    @Test
    void anAdministratorLockDuringTheWaitAnswers401() throws Exception {
        UUID admin = applications.staff("Quản trị viên", "admin@staff.test");
        try (Connection blocker = dataSource.getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try (var update = blocker.prepareStatement("UPDATE user_accounts SET admin_locked_at = ?,"
                    + " admin_lock_reason = 'Kiểm tra', admin_locked_by = ? WHERE id = ?")) {
                update.setTimestamp(1, Timestamp.from(ApplicationTestSupport.NOW));
                update.setObject(2, admin);
                update.setObject(3, referrer);
                assertThat(update.executeUpdate()).isEqualTo(1);
            }
            Future<HttpResponse<String>> referral;
            try {
                referral = executor.submit(() -> applications.refer(token, posting, EMAIL));
                applications.awaitWaiterOf(ApplicationTestSupport.backendPid(blocker));
                // The quota (KEY SHARE on the account through its key) was not blocked: the attempt is already counted.
                assertThat(applications.referralAttempts()).isEqualTo(1);
                blocker.commit();
            } finally {
                blocker.rollback();         // a no-op after the commit; frees the referral if an await failed
            }
            var response = referral.get(10, TimeUnit.SECONDS);
            status(response, 401);
            assertThat(json.readTree(response.body()).get("code").asString()).isEqualTo("SESSION_INVALID");
        }
        assertThat(applicationsOf(posting)).isZero();
        assertThat(outcomes()).containsExactly("REFUSED");
    }

    // SELF_PROFILE_WRITE removed from the caller's role while the referral waits on the requisition, which the waited-on
    // transaction then commits FILLED: the check right after the requisition lock answers 403, before store() could
    // answer 404 (403 before 404); nothing is stored; the attempt is REFUSED.
    @Test
    void aPermissionRemovedDuringTheRequisitionWaitAnswers403Before404() throws Exception {
        try (Connection blocker = dataSource.getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try (var update = blocker.prepareStatement("UPDATE recruitment_requisitions SET status = 'FILLED' WHERE id = ?")) {
                update.setObject(1, requisition);
                assertThat(update.executeUpdate()).isEqualTo(1);
            }
            Future<HttpResponse<String>> referral;
            try {
                referral = executor.submit(() -> applications.refer(token, posting, EMAIL));
                applications.awaitWaiterOf(ApplicationTestSupport.backendPid(blocker));
                removeReferralPermission();
                blocker.commit();
            } finally {
                blocker.rollback();         // a no-op after the commit; frees the referral if an await failed
            }
            var response = referral.get(10, TimeUnit.SECONDS);
            status(response, 403);
            assertThat(json.readTree(response.body()).get("code").asString()).isEqualTo("FORBIDDEN");
        } finally {
            restoreReferralPermission();
        }
        assertThat(applicationsOf(posting)).isZero();
        assertThat(outcomes()).containsExactly("REFUSED");
    }

    // The same while the referral waits on the posting: the gate callback answers 403 before storing candidate data.
    @Test
    void aPermissionRemovedDuringThePostingWaitAnswers403() throws Exception {
        try (Connection blocker = dataSource.getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try (var hold = blocker.prepareStatement("SELECT 1 FROM job_postings WHERE id = ? FOR UPDATE")) {
                hold.setObject(1, posting);
                hold.executeQuery().close();
            }
            Future<HttpResponse<String>> referral;
            try {
                referral = executor.submit(() -> applications.refer(token, posting, EMAIL));
                applications.awaitWaiterOf(ApplicationTestSupport.backendPid(blocker));
                removeReferralPermission();
            } finally {
                blocker.rollback();
            }
            var response = referral.get(10, TimeUnit.SECONDS);
            status(response, 403);
            assertThat(json.readTree(response.body()).get("code").asString()).isEqualTo("FORBIDDEN");
        } finally {
            restoreReferralPermission();
        }
        assertThat(applicationsOf(posting)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_applications WHERE normalized_email = ?", Integer.class,
                EMAIL)).isZero();
        assertThat(outcomes()).containsExactly("REFUSED");
    }

    @Test
    void aTokenThatExpiresDuringThePostingWaitAnswers401WithoutWritingCandidateData() throws Exception {
        try (Connection blocker = dataSource.getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try (var hold = blocker.prepareStatement("SELECT 1 FROM job_postings WHERE id = ? FOR UPDATE")) {
                hold.setObject(1, posting);
                hold.executeQuery().close();
            }
            Future<HttpResponse<String>> referral;
            try {
                referral = executor.submit(() -> applications.refer(token, posting, EMAIL));
                applications.awaitWaiterOf(ApplicationTestSupport.backendPid(blocker));
                clock.set(ApplicationTestSupport.NOW.plus(TokenService.ACCESS_TOKEN_TTL).plusSeconds(1));
            } finally {
                blocker.rollback();
            }
            var response = referral.get(10, TimeUnit.SECONDS);
            status(response, 401);
            assertThat(json.readTree(response.body()).get("code").asString()).isEqualTo("SESSION_INVALID");
        }
        assertThat(applicationsOf(posting)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidates WHERE normalized_email = ?", Integer.class,
                EMAIL)).isZero();
        assertThat(outcomes()).containsExactly("REFUSED");
    }

    private void removeReferralPermission() {
        assertThat(jdbc.update("DELETE FROM role_permissions WHERE role_code = 'RECRUITER'"
                + " AND permission_code = 'SELF_PROFILE_WRITE'")).isEqualTo(1);
    }

    private void restoreReferralPermission() {
        jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('RECRUITER', 'SELF_PROFILE_WRITE')"
                + " ON CONFLICT DO NOTHING");
    }

    // While one referral is being written (here waiting on the requisition), the next one of any referrer meets the
    // busy gate: 503 REFERRAL_BUSY with Retry-After: 1, before its form is read, so it uses no attempt.
    @Test
    void aSecondReferralIsBusyWhileOneIsBeingWritten() throws Exception {
        String other = applications.token(tokens, applications.account("Lê Văn Bình", "binh@staff.test", Role.APPROVER));
        try (Connection blocker = dataSource.getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try (var hold = blocker.prepareStatement("SELECT 1 FROM recruitment_requisitions WHERE id = ? FOR UPDATE")) {
                hold.setObject(1, requisition);
                hold.executeQuery().close();
            }
            Future<HttpResponse<String>> referral;
            try {
                referral = executor.submit(() -> applications.refer(token, posting, EMAIL));
                applications.awaitWaiterOf(ApplicationTestSupport.backendPid(blocker));
                var busy = applications.refer(other, posting, "binh.candidate@example.test");
                status(busy, 503);
                assertThat(json.readTree(busy.body()).get("code").asString()).isEqualTo("REFERRAL_BUSY");
                assertThat(json.readTree(busy.body()).get("message").asString())
                        .isEqualTo("Hệ thống đang bận. Vui lòng thử lại sau.");
                assertThat(busy.headers().firstValue("Retry-After")).contains("1");
                assertThat(applications.referralAttempts()).isEqualTo(1);
            } finally {
                blocker.rollback();
            }
            status(referral.get(10, TimeUnit.SECONDS), 201);
        }
        status(applications.refer(other, posting, "binh.candidate@example.test"), 201);
        assertThat(outcomes()).containsExactly("STORED", "STORED");
    }

    private void status(HttpResponse<String> response, int expected) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expected);
    }

    private HttpResponse<String> close(UUID id, String bearer) throws Exception {
        return applications.post("/api/v1/requisitions/" + id + "/status-changes",
                "{\"status\":\"FILLED\",\"reason\":\"Đã tuyển đủ\",\"expectedRevision\":0}", "application/json",
                Map.of("Authorization", "Bearer " + bearer));
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

    private int applicationsOf(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM job_applications WHERE job_posting_id = ?", Integer.class, id);
    }

    private List<String> outcomes() {
        return jdbc.queryForList("SELECT outcome FROM referral_attempts ORDER BY id", String.class);
    }
}
