package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.account.BootstrapAdmin;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.jobposting.JobPostingApplicationGate;
import vn.ttcs.recruitment.jobposting.JobPostingApplicationGate.OpenJobPosting;
import vn.ttcs.recruitment.requisition.RequisitionRecruiterRole;
import vn.ttcs.recruitment.requisition.RequisitionStatus;

import javax.sql.DataSource;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.ttcs.recruitment.catalog.RecruitmentCatalogType.EMPLOYMENT_TYPE;
import static vn.ttcs.recruitment.catalog.RecruitmentCatalogType.WORK_LOCATION;

// Task 355 (story S4-08): taking a posting down at the same moment as something else. Like every posting write, it
// locks the caller's account and session, then the requisition row, then the posting row, and checks the caller again
// after each lock. Waiters on a row lock are served in arrival order, so each race is staged by holding a row with a
// raw connection, sending the requests one after the other and releasing the row, like
// JobPostingClosureLockIntegrationTest.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class JobPostingUnpublishLockIntegrationTest {
    private static final Instant START = Instant.parse("2026-10-11T03:00:00Z");
    private static final String REASON = "Đã nhận đủ hồ sơ, gỡ tin.";
    private static final String CLOSED_WITH_REQUISITION = "Tin tuyển dụng đã đóng cùng yêu cầu tuyển dụng nên không "
            + "thể sửa, gửi duyệt, duyệt, xuất bản hay gỡ nữa.";
    // The lock of every requisition write, which the gate (task 321) also takes; both wait for it, as every posting
    // write does.
    private static final String HOLD_REQUISITION =
            "SELECT pg_backend_pid() FROM recruitment_requisitions WHERE id = ? FOR NO KEY UPDATE";
    private static final String SHARE_REQUISITION =
            "SELECT pg_backend_pid() FROM recruitment_requisitions WHERE id = ? FOR SHARE";
    private static final String SHARE_POSTING = "SELECT pg_backend_pid() FROM job_postings WHERE id = ? FOR SHARE";
    // The first two locks of every posting write (JobPostingAccess.lockWriter): the caller's account, then its session.
    private static final String HOLD_ACCOUNT = "SELECT pg_backend_pid() FROM user_accounts WHERE id = ? FOR UPDATE";
    private static final String HOLD_SESSION =
            "SELECT pg_backend_pid() FROM auth_sessions WHERE user_id = ? FOR UPDATE";
    private static final String REASON_REQUIRED = "Vui lòng nhập lý do gỡ tin tuyển dụng.";

    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private AccountRepository accounts;
    @Autowired private BootstrapAdmin bootstrap;
    @Autowired private AuthIntegrationTest.MutableClock clock;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JobPostingApplicationGate gate;

    private RequisitionStatusTestSupport status;
    private JobPostingTestSupport postings;
    private ApprovalTestSupport api;
    private String passwordHash;
    private UUID adminId;
    private String adminToken;
    private UUID hrId;
    private String hrToken;
    private UUID recruiterId;
    private String recruiterToken;
    private UUID successorId;
    private UUID requisition;
    private UUID posting;

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
        restorePermissions();
        status = new RequisitionStatusTestSupport(jdbc, json, transactions,
                Integer.parseInt(environment.getRequiredProperty("local.server.port")));
        postings = status.postings;
        api = status.api;
        status.reset();
        bootstrap.run(new DefaultApplicationArguments());
        adminId = jdbc.queryForObject("SELECT id FROM user_accounts WHERE email = 'admin@example.test'", UUID.class);
        passwordHash = accounts.findById(adminId).orElseThrow().getPasswordHash();
        // One login each, so every account has exactly one session.
        adminToken = api.token("admin@example.test");
        hrId = account("hr@example.test", Role.HR_MANAGER);
        hrToken = api.token("hr@example.test");
        UUID headId = account("head@example.test", Role.HIRING_MANAGER);
        recruiterId = account("recruiter@example.test", Role.RECRUITER);
        recruiterToken = api.token("recruiter@example.test");
        successorId = account("successor@example.test", Role.RECRUITER);
        UUID department = api.department("IT", headId, null, START);
        UUID position = api.position(START);
        UUID workLocation = postings.catalogValue(WORK_LOCATION, "HN", "Hà Nội", true, START);
        UUID employmentType = postings.catalogValue(EMPLOYMENT_TYPE, "FULL_TIME", "Toàn thời gian", true, START);
        // An approved requisition written with SQL, without history (revision 0), whose primary recruiter is the
        // recruiter; HR writes, sends and publishes the posting, ADMIN approves it (revision 4).
        requisition = postings.requisition(position, department, adminId, RequisitionStatus.APPROVED, START);
        postings.assign(requisition, recruiterId, RequisitionRecruiterRole.PRIMARY, hrId, START);
        posting = status.publishedPosting(requisition, workLocation, employmentType, hrToken, adminToken);
    }

    // The seed gives RECRUITER JOB_POSTINGS_WRITE_SCOPED and JOB_POSTINGS_READ_SCOPED; some cases remove one.
    @AfterEach
    void restorePermissions() {
        jdbc.update("INSERT INTO role_permissions (role_code, permission_code) "
                + "VALUES ('RECRUITER', 'JOB_POSTINGS_WRITE_SCOPED'), ('RECRUITER', 'JOB_POSTINGS_READ_SCOPED') "
                + "ON CONFLICT DO NOTHING");
    }

    // Taken down first, then the requisition filled: the posting is closed from UNPUBLISHED. Filled first: the
    // take-down is told the posting closed with its requisition, with the message that names taking down.
    @ParameterizedTest(name = "{0} then {1}")
    @CsvSource({"unpublish, fill", "fill, unpublish"})
    void aTakeDownAndTheClosureOfTheRequisition(String first, String second) throws Exception {
        Callable<HttpResponse<String>> unpublish = () -> postings.unpublish(posting, 4, REASON, recruiterToken);
        Callable<HttpResponse<String>> fill = () -> status.change(requisition, "FILLED", "Đã tuyển đủ", 0, hrToken);
        boolean takenDownFirst = first.equals("unpublish");

        var responses = staged(SHARE_REQUISITION, requisition, takenDownFirst ? unpublish : fill,
                takenDownFirst ? fill : unpublish);

        assertThat(status.ok(responses.get(takenDownFirst ? 1 : 0)).path("status").asText()).isEqualTo("FILLED");
        var takeDown = responses.get(takenDownFirst ? 0 : 1);
        List<Map<String, Object>> events = postings.events(posting);
        if (takenDownFirst) {
            assertThat(api.expect(takeDown, 200).path("status").asText()).isEqualTo("UNPUBLISHED");
            assertThat(events).extracting(event -> event.get("event_type"))
                    .containsExactly("SUBMITTED", "APPROVED", "PUBLISHED", "UNPUBLISHED", "CLOSED");
            assertThat(events.getLast()).containsEntry("status_before", "UNPUBLISHED");
        } else {
            JsonNode error = postings.error(takeDown, 409, "JOB_POSTING_CLOSED");
            assertThat(error.path("message").asText()).isEqualTo(CLOSED_WITH_REQUISITION);
            assertThat(events).extracting(event -> event.get("event_type"))
                    .containsExactly("SUBMITTED", "APPROVED", "PUBLISHED", "CLOSED");
            assertThat(events.getLast()).containsEntry("status_before", "PUBLISHED");
        }
        assertThat(postings.row(posting).get("status")).isEqualTo("CLOSED");
        postings.error(postings.publicDetail(posting), 404, "JOB_POSTING_NOT_FOUND");
    }

    // The recruiter and HR take the same posting down at the same moment: it is taken down once; the second is told
    // so. job_posting_events_one_unpublication_idx is the last line.
    @Test
    void twoTakeDownsAtOnceRecordOne() throws Exception {
        var responses = staged(SHARE_REQUISITION, requisition,
                () -> postings.unpublish(posting, 4, REASON, recruiterToken),
                () -> postings.unpublish(posting, 4, "Gỡ tin giúp recruiter.", hrToken));

        assertThat(api.expect(responses.get(0), 200).path("status").asText()).isEqualTo("UNPUBLISHED");
        postings.error(responses.get(1), 409, "JOB_POSTING_ALREADY_UNPUBLISHED");
        assertThat(postings.events(posting).stream().filter(event -> event.get("event_type").equals("UNPUBLISHED")))
                .singleElement().satisfies(event -> assertThat(event).containsEntry("actor_id", recruiterId)
                        .containsEntry("comment", REASON));
        assertThat(postings.revision(posting)).isEqualTo(5);
    }

    // An application holds the requisition (FOR NO KEY UPDATE) and the posting (FOR SHARE) until it commits (task 321):
    // a take-down waits for it, so no application commits to a posting already taken down, and then succeeds.
    @Test
    void aTakeDownWaitsForAnApplicationInProgress() throws Exception {
        CountDownLatch checked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger applicant = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<OpenJobPosting> application = executor.submit(() -> new TransactionTemplate(transactions)
                    .execute(transaction -> {
                        OpenJobPosting open = gate.requireOpen(posting);
                        applicant.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                        checked.countDown();
                        await(release);
                        return open;
                    }));
            assertThat(checked.await(20, TimeUnit.SECONDS)).isTrue();
            Future<HttpResponse<String>> takeDown = executor.submit(() ->
                    postings.unpublish(posting, 4, REASON, recruiterToken));
            awaitWaiters(applicant.get(), 1);
            assertThat(takeDown.isDone()).isFalse();
            assertThat(postings.row(posting).get("status")).isEqualTo("PUBLISHED");

            release.countDown();
            assertThat(application.get(20, TimeUnit.SECONDS).postingId()).isEqualTo(posting);
            assertThat(api.expect(takeDown.get(20, TimeUnit.SECONDS), 200).path("status").asText())
                    .isEqualTo("UNPUBLISHED");
        }
        assertThat(postings.row(posting).get("status")).isEqualTo("UNPUBLISHED");
    }

    // A take-down reaches the requisition before an application: the application waits behind it, then finds the
    // posting taken down and gets the 404 of an id that does not exist.
    @Test
    void anApplicationQueuedBehindATakeDownIsNotFound() throws Exception {
        try (var connection = dataSource.getConnection(); var executor = Executors.newFixedThreadPool(2)) {
            connection.setAutoCommit(false);
            int blocker = lock(connection, HOLD_REQUISITION, requisition);
            Future<HttpResponse<String>> takeDown = executor.submit(() ->
                    postings.unpublish(posting, 4, REASON, recruiterToken));
            awaitWaiters(blocker, 1);
            Future<Object> application = executor.submit(() -> application(posting));
            awaitWaiters(blocker, 2);
            connection.commit();
            assertThat(api.expect(takeDown.get(20, TimeUnit.SECONDS), 200).path("status").asText())
                    .isEqualTo("UNPUBLISHED");
            assertNotFound(application.get(20, TimeUnit.SECONDS));
        }
    }

    // The same when the row is held by an application in progress: a take-down waits for it, and an application that
    // arrives while the take-down waits queues behind the take-down (the gate locks the requisition FOR NO KEY UPDATE,
    // and waiters are served in arrival order), so the take-down commits first and that application gets the 404. A
    // share lock on the requisition would let the later application through at once, ahead of the take-down, and a
    // steady stream of applications could keep the take-down waiting.
    @Test
    void anApplicationArrivingBehindAWaitingTakeDownIsNotFound() throws Exception {
        CountDownLatch checked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger applicant = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(3)) {
            Future<OpenJobPosting> first = executor.submit(() -> new TransactionTemplate(transactions)
                    .execute(transaction -> {
                        OpenJobPosting open = gate.requireOpen(posting);
                        applicant.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                        checked.countDown();
                        await(release);
                        return open;
                    }));
            assertThat(checked.await(20, TimeUnit.SECONDS)).isTrue();
            Future<HttpResponse<String>> takeDown = executor.submit(() ->
                    postings.unpublish(posting, 4, REASON, recruiterToken));
            awaitWaiters(applicant.get(), 1);
            Future<Object> later = executor.submit(() -> application(posting));
            awaitWaiters(applicant.get(), 2);
            assertThat(takeDown.isDone()).isFalse();
            assertThat(later.isDone()).isFalse();

            release.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS).postingId()).isEqualTo(posting);
            assertThat(api.expect(takeDown.get(20, TimeUnit.SECONDS), 200).path("status").asText())
                    .isEqualTo("UNPUBLISHED");
            assertNotFound(later.get(20, TimeUnit.SECONDS));
        }
        assertThat(postings.row(posting).get("status")).isEqualTo("UNPUBLISHED");
    }

    // The recruiter takes the posting down while a row it needs is held; whatever changed meanwhile is checked once
    // the lock is granted: an expired token (401), a removed permission (403) and a handover of the requisition to
    // another recruiter (403). Nothing is written then.
    @ParameterizedTest(name = "{0} held, then {1}")
    @CsvSource({
            "requisition, nothing, 200", "posting, nothing, 200",
            "requisition, token-expired, 401", "posting, token-expired, 401",
            "requisition, permission-lost, 403", "posting, permission-lost, 403",
            "requisition, handover, 403"
    })
    void theCallerIsCheckedAgainAfterEachLockWait(String held, String change, int expected) throws Exception {
        List<Map<String, Object>> postingsBefore = jdbc.queryForList("SELECT * FROM job_postings ORDER BY id");
        List<Map<String, Object>> eventsBefore = jdbc.queryForList("SELECT * FROM job_posting_events ORDER BY 1");
        HttpResponse<String> result;
        try (var connection = dataSource.getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            int blocker = held.equals("requisition") ? lock(connection, SHARE_REQUISITION, requisition)
                    : lock(connection, SHARE_POSTING, posting);
            var response = executor.submit(() -> postings.unpublish(posting, 4, REASON, recruiterToken));
            awaitWaiters(blocker, 1);
            switch (change) {
                case "token-expired" -> clock.set(START.plus(Duration.ofMinutes(16)));
                case "permission-lost" -> jdbc.update("DELETE FROM role_permissions WHERE role_code = 'RECRUITER' "
                        + "AND permission_code = 'JOB_POSTINGS_WRITE_SCOPED'");
                case "handover" -> execute(connection, "UPDATE requisition_recruiters SET recruiter_id = ? "
                        + "WHERE requisition_id = ? AND recruiter_id = ?", successorId, requisition, recruiterId);
                default -> { }
            }
            connection.commit();
            result = response.get(20, TimeUnit.SECONDS);
        }

        assertThat(result.statusCode()).as(result.body()).isEqualTo(expected);
        if (expected == 200) {
            assertThat(json.readTree(result.body()).path("status").asText()).isEqualTo("UNPUBLISHED");
            return;
        }
        assertThat(json.readTree(result.body()).path("code").asText())
                .isEqualTo(expected == 401 ? "SESSION_INVALID" : "FORBIDDEN");
        assertThat(jdbc.queryForList("SELECT * FROM job_postings ORDER BY id")).isEqualTo(postingsBefore);
        assertThat(jdbc.queryForList("SELECT * FROM job_posting_events ORDER BY 1")).isEqualTo(eventsBefore);
    }

    // The reason is checked before any lock, the caller's own account and session included: a take-down without one
    // is answered at once while another write holds any of the three rows, and a take-down with one waits for it.
    @ParameterizedTest(name = "{0} held")
    @ValueSource(strings = {"account", "session", "requisition"})
    void aMissingReasonIsAnsweredBeforeAnyLock(String held) throws Exception {
        String sql = switch (held) {
            case "account" -> HOLD_ACCOUNT;
            case "session" -> HOLD_SESSION;
            default -> HOLD_REQUISITION;
        };
        // The recruiter logged in once, so it has exactly one session.
        UUID heldId = held.equals("requisition") ? requisition : recruiterId;
        try (var connection = dataSource.getConnection(); var executor = Executors.newFixedThreadPool(2)) {
            connection.setAutoCommit(false);
            int blocker = lock(connection, sql, heldId);

            var missing = executor.submit(() -> postings.unpublish(posting, 4, null, recruiterToken));
            postings.businessError(missing.get(10, TimeUnit.SECONDS), "JOB_POSTING_UNPUBLISH_REASON_REQUIRED",
                    "reason", REASON_REQUIRED);

            var given = executor.submit(() -> postings.unpublish(posting, 4, REASON, recruiterToken));
            awaitWaiters(blocker, 1);
            assertThat(given.isDone()).isFalse();
            assertThat(postings.row(posting).get("status")).isEqualTo("PUBLISHED");
            connection.commit();
            assertThat(api.expect(given.get(20, TimeUnit.SECONDS), 200).path("status").asText())
                    .isEqualTo("UNPUBLISHED");
        }
    }

    // The reason comes before the caller's permissions too (they are read after the account and session locks). The
    // matcher only asks for the write permission, so a recruiter who lost the read permission of postings reaches the
    // service: without a reason it is told the reason is missing, with one it is refused, and nothing is written.
    @Test
    void aMissingReasonIsAnsweredBeforeTheCallerIsChecked() throws Exception {
        List<Map<String, Object>> postingsBefore = jdbc.queryForList("SELECT * FROM job_postings ORDER BY id");
        List<Map<String, Object>> eventsBefore = jdbc.queryForList("SELECT * FROM job_posting_events ORDER BY 1");
        jdbc.update("DELETE FROM role_permissions WHERE role_code = 'RECRUITER' "
                + "AND permission_code = 'JOB_POSTINGS_READ_SCOPED'");

        for (UUID id : List.of(posting, UUID.randomUUID())) {
            postings.businessError(postings.unpublish(id, 4, null, recruiterToken),
                    "JOB_POSTING_UNPUBLISH_REASON_REQUIRED", "reason", REASON_REQUIRED);
            postings.forbidden(postings.unpublish(id, 4, REASON, recruiterToken));
        }

        assertThat(jdbc.queryForList("SELECT * FROM job_postings ORDER BY id")).isEqualTo(postingsBefore);
        assertThat(jdbc.queryForList("SELECT * FROM job_posting_events ORDER BY 1")).isEqualTo(eventsBefore);
    }

    // Holds the row, sends first and waits until it waits for the row, then sends second and waits for it too, and
    // releases the row: first runs, then second.
    private List<HttpResponse<String>> staged(String sql, UUID id, Callable<HttpResponse<String>> first,
                                              Callable<HttpResponse<String>> second) throws Exception {
        List<HttpResponse<String>> results = new ArrayList<>();
        try (var connection = dataSource.getConnection(); var executor = Executors.newFixedThreadPool(2)) {
            connection.setAutoCommit(false);
            int blocker = lock(connection, sql, id);
            Future<HttpResponse<String>> firstResponse = executor.submit(first);
            awaitWaiters(blocker, 1);
            Future<HttpResponse<String>> secondResponse = executor.submit(second);
            awaitWaiters(blocker, 2);
            connection.commit();
            results.add(firstResponse.get(20, TimeUnit.SECONDS));
            results.add(secondResponse.get(20, TimeUnit.SECONDS));
        }
        return results;
    }

    // The gate the apply API (task 326) calls, in its own transaction: the posting it returns or its refusal.
    private Object application(UUID id) {
        try {
            return new TransactionTemplate(transactions).execute(transaction -> gate.requireOpen(id));
        } catch (ApiException refusal) {
            return refusal;
        }
    }

    private static void assertNotFound(Object outcome) {
        assertThat(outcome).isInstanceOfSatisfying(ApiException.class, refusal -> {
            assertThat(refusal.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(refusal.getCode()).isEqualTo("JOB_POSTING_NOT_FOUND");
        });
    }

    private UUID account(String email, Role role) {
        return accounts.saveAndFlush(new Account(email, "Lock test", passwordHash, Set.of(role), START)).getId();
    }

    private static int lock(Connection connection, String sql, UUID id) throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (var row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getInt(1);
            }
        }
    }

    private static void execute(Connection connection, String sql, Object... values) throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(20, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    // Waits until this many sessions wait, directly or through another waiter, for a lock the blocker holds.
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
            if (observed >= expected) {
                return;
            }
            Thread.sleep(20);
        }
        assertThat(observed).as("the requests must wait for the blocker's locks").isGreaterThanOrEqualTo(expected);
    }
}
