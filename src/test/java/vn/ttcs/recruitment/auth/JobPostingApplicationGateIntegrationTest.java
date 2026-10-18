package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
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
import vn.ttcs.recruitment.requisition.RequisitionStatus;

import javax.sql.DataSource;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static vn.ttcs.recruitment.catalog.RecruitmentCatalogType.EMPLOYMENT_TYPE;
import static vn.ttcs.recruitment.catalog.RecruitmentCatalogType.WORK_LOCATION;

// Task 321 (story S4-02): "Kiểm tra khả năng ứng tuyển theo trạng thái tin". JobPostingApplicationGate is the check the
// apply API (task 326, story S4-03) makes before it writes an application: it passes only while the posting is open
// (published, requisition approved, deadline today or later in Vietnam), refuses an expired posting with 409 and
// every other one with the 404 of an unknown id, and keeps the posting so until the application commits. There is no
// apply API yet, so the gate is called as 326 will call it: first, in a READ COMMITTED read-write transaction.
// The posting is published through the API with the deadline 30 Nov 2026; applications close at 00:00 on 1 Dec in
// Vietnam (17:00 UTC on 30 Nov). Races are staged like JobPostingClosureLockIntegrationTest: a raw connection holds a
// row, the requests queue behind it in arrival order (pg_blocking_pids), then the row is released.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class JobPostingApplicationGateIntegrationTest {
    private static final Instant START = Instant.parse("2026-10-11T03:00:00Z");
    // 23:59:59 on the deadline day in Vietnam, and one second later, 00:00 on the next day.
    private static final Instant LAST_SECOND = Instant.parse("2026-11-30T16:59:59Z");
    private static final Instant CLOSED = Instant.parse(JobPostingTestSupport.CLOSES_AT);
    private static final LocalDate DEADLINE = LocalDate.parse(JobPostingTestSupport.DEADLINE);
    // The lock of every requisition write, which the gate also takes on the requisition.
    private static final String HOLD_REQUISITION =
            "SELECT pg_backend_pid() FROM recruitment_requisitions WHERE id = ? FOR NO KEY UPDATE";
    private static final String EXPIRED_MESSAGE = "Tin tuyển dụng đã hết hạn nộp hồ sơ.";
    private static final String NOT_FOUND_MESSAGE = "Không tìm thấy tin tuyển dụng.";

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
    private String adminToken;
    private String hrToken;
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
        status = new RequisitionStatusTestSupport(jdbc, json, transactions,
                Integer.parseInt(environment.getRequiredProperty("local.server.port")));
        postings = status.postings;
        api = status.api;
        status.reset();
        bootstrap.run(new DefaultApplicationArguments());
        UUID adminId = jdbc.queryForObject("SELECT id FROM user_accounts WHERE email = 'admin@example.test'",
                UUID.class);
        passwordHash = accounts.findById(adminId).orElseThrow().getPasswordHash();
        adminToken = api.token("admin@example.test");
        account("hr@example.test", Role.HR_MANAGER);
        hrToken = api.token("hr@example.test");
        UUID headId = account("head@example.test", Role.HIRING_MANAGER);
        UUID department = api.department("IT", headId, null, START);
        UUID position = api.position(START);
        UUID workLocation = postings.catalogValue(WORK_LOCATION, "HN", "Hà Nội", true, START);
        UUID employmentType = postings.catalogValue(EMPLOYMENT_TYPE, "FULL_TIME", "Toàn thời gian", true, START);
        // An approved requisition written with SQL, without history (revision 0); HR writes, sends and publishes
        // the posting, ADMIN approves it.
        requisition = postings.requisition(position, department, adminId, RequisitionStatus.APPROVED, START);
        posting = status.publishedPosting(requisition, workLocation, employmentType, hrToken, adminToken);
    }

    // An open posting passes with its ids and deadline. The gate's transaction then holds the requisition FOR NO KEY
    // UPDATE and the posting FOR SHARE until it commits: a second applicant to the posting, an UPDATE of the posting
    // (FOR NO KEY UPDATE, the lock of a closure or any posting write) and HR's pause of the requisition all wait, and
    // run once the application commits, the second applicant before the pause, which arrived after it. Applicants to
    // one requisition run one after another: a share lock on the requisition would let the second one through at once,
    // and so every later applicant ahead of a waiting pause. The UPDATE touches a non-key column of the posting row
    // only (not id, not requisition_id, V21's unique key): FOR KEY SHARE would let it through, so this is the test that
    // pins the posting's lock mode.
    @Test
    void anOpenPostingPassesAndHoldsBothRowsUntilTheApplicationCommits() throws Exception {
        CountDownLatch checked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger applicant = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(4)) {
            Future<OpenJobPosting> application = executor.submit(() -> new TransactionTemplate(transactions)
                    .execute(transaction -> {
                        OpenJobPosting open = gate.requireOpen(posting);
                        applicant.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                        checked.countDown();
                        await(release);
                        return open;
                    }));
            assertThat(checked.await(20, TimeUnit.SECONDS)).isTrue();

            // A second applicant waits for the first.
            Future<OpenJobPosting> second = executor.submit(() -> apply(posting));
            awaitWaiters(applicant.get(), 1);
            Future<Integer> update = executor.submit(() ->
                    jdbc.update("UPDATE job_postings SET title = title WHERE id = ?", posting));
            awaitWaiters(applicant.get(), 2);
            Future<HttpResponse<String>> pause = executor.submit(() ->
                    status.change(requisition, "PAUSED", "Chờ ngân sách", api.lastRevision(requisition), hrToken));
            awaitWaiters(applicant.get(), 3);
            assertThat(second.isDone()).isFalse();
            assertThat(update.isDone()).isFalse();
            assertThat(pause.isDone()).isFalse();
            assertThat(api.status(requisition)).isEqualTo("APPROVED");

            release.countDown();
            assertThat(application.get(20, TimeUnit.SECONDS))
                    .isEqualTo(new OpenJobPosting(posting, requisition, DEADLINE));
            // It reached the requisition before the pause, so it still passes.
            assertThat(second.get(20, TimeUnit.SECONDS)).isEqualTo(new OpenJobPosting(posting, requisition, DEADLINE));
            assertThat(update.get(20, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(status.ok(pause.get(20, TimeUnit.SECONDS)).path("status").asText()).isEqualTo("PAUSED");
        }
        assertThat(postings.row(posting).get("status")).isEqualTo("PUBLISHED");
    }

    // The deadline is a date in Vietnam: a page opened at 23:59:59 on the deadline day shows the apply form, and an
    // application sent one second later, at 00:00, is refused with the closed notice. Nothing has to run first: the
    // posting is still PUBLISHED.
    @Test
    void anApplicationSentAfterMidnightIsRefusedThoughThePageWasOpenedBefore() throws Exception {
        clock.set(LAST_SECOND);
        JobPostingTestSupport.assertApplicationState(detail(), true);
        assertThat(apply(posting)).isEqualTo(new OpenJobPosting(posting, requisition, DEADLINE));

        clock.set(CLOSED);
        expired(posting);
        assertThat(postings.row(posting).get("status")).isEqualTo("PUBLISHED");
        // The page, loaded again, agrees.
        JobPostingTestSupport.assertApplicationState(detail(), false);
    }

    // Today is read after the locks: an application that waits for a requisition write from 23:59:59 until after
    // midnight is judged on the new date.
    @Test
    void anApplicationThatWaitsPastMidnightUsesTheNewDate() throws Exception {
        clock.set(LAST_SECOND);
        try (var connection = dataSource.getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            int blocker = lock(connection, requisition);
            Future<Object> application = executor.submit(() -> outcome(posting));
            awaitWaiters(blocker, 1);
            clock.set(CLOSED);
            connection.commit();
            assertExpired(application.get(20, TimeUnit.SECONDS));
        }
    }

    // HR closes the requisition as filled while an application arrives: the closure reaches the requisition first,
    // the application queues behind it, sees the posting CLOSED (task 292) and gets the 404 of an unknown id.
    @Test
    void anApplicationQueuedBehindTheClosureOfItsRequisitionIsNotFound() throws Exception {
        try (var connection = dataSource.getConnection(); var executor = Executors.newFixedThreadPool(2)) {
            connection.setAutoCommit(false);
            int blocker = lock(connection, requisition);
            Future<HttpResponse<String>> closure = executor.submit(() ->
                    status.change(requisition, "FILLED", "Đã tuyển đủ", 0, hrToken));
            awaitWaiters(blocker, 1);
            Future<Object> application = executor.submit(() -> outcome(posting));
            awaitWaiters(blocker, 2);
            connection.commit();
            assertThat(status.ok(closure.get(20, TimeUnit.SECONDS)).path("status").asText()).isEqualTo("FILLED");
            assertNotFound(application.get(20, TimeUnit.SECONDS));
        }
        assertThat(postings.row(posting).get("status")).isEqualTo("CLOSED");
    }

    // A paused requisition hides its posting from the portal (task 309) and takes no applications; resumed, it takes
    // them again.
    @Test
    void aPausedRequisitionTakesNoApplicationsUntilItIsResumed() throws Exception {
        status.ok(status.change(requisition, "PAUSED", "Chờ ngân sách", 0, hrToken));
        notFound(posting);
        postings.error(postings.publicDetail(posting), 404, "JOB_POSTING_NOT_FOUND");

        status.ok(status.change(requisition, "APPROVED", null, api.lastRevision(requisition), hrToken));
        assertThat(apply(posting)).isEqualTo(new OpenJobPosting(posting, requisition, DEADLINE));
    }

    // An id that does not exist is the same 404.
    @Test
    void anUnknownPostingIsNotFound() {
        notFound(UUID.randomUUID());
    }

    // The gate must run inside the caller's transaction (its locks last until the application commits), which
    // must be read-write and READ COMMITTED: a programming error otherwise, refused before any SQL.
    @Test
    void theGateNeedsTheCallersReadWriteReadCommittedTransaction() {
        assertThatThrownBy(() -> gate.requireOpen(posting)).isInstanceOf(IllegalTransactionStateException.class);
        for (int isolation : List.of(TransactionDefinition.ISOLATION_REPEATABLE_READ,
                TransactionDefinition.ISOLATION_SERIALIZABLE)) {
            TransactionTemplate template = new TransactionTemplate(transactions);
            template.setIsolationLevel(isolation);
            assertMisused(template);
        }
        TransactionTemplate readOnly = new TransactionTemplate(transactions);
        readOnly.setReadOnly(true);
        assertMisused(readOnly);

        TransactionTemplate readCommitted = new TransactionTemplate(transactions);
        readCommitted.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        OpenJobPosting open = readCommitted.execute(transaction -> gate.requireOpen(posting));
        assertThat(open).isEqualTo(new OpenJobPosting(posting, requisition, DEADLINE));
    }

    // The gate only reads and locks: whatever it answers, no row of the posting, its history, its authors or its
    // requisition changes, not even its row version (xmin; a lock leaves it alone, an UPDATE does not).
    @Test
    void theGateWritesNothing() throws Exception {
        Map<String, List<Map<String, Object>>> before = snapshot();

        apply(posting);
        notFound(UUID.randomUUID());
        clock.set(CLOSED);
        expired(posting);
        assertThat(snapshot()).isEqualTo(before);

        clock.set(START); // HR's token is no longer valid on 1 Dec
        status.ok(status.change(requisition, "PAUSED", "Chờ ngân sách", 0, hrToken));
        Map<String, List<Map<String, Object>>> paused = snapshot();
        notFound(posting);
        assertThat(snapshot()).isEqualTo(paused);
    }

    // A refusal does not mark the caller's transaction rollback-only (noRollbackFor): the apply API may catch the 409
    // or 404, for example to record the refused attempt, and still commit, instead of the commit failing with
    // UnexpectedRollbackException (a 500 for the candidate). Let out of the transaction, a refusal rolls it back as
    // any exception does. The refusals are an expired posting (thrown after both locks) and an unknown id (thrown
    // before any lock); the write is a harmless rename of an account.
    @Test
    void aCaughtRefusalLeavesTheCallersTransactionFreeToCommit() {
        clock.set(CLOSED);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(transaction -> {
            rename("Refused, rolled back");
            return gate.requireOpen(posting);
        })).isInstanceOf(ApiException.class).hasMessage(EXPIRED_MESSAGE);
        assertThat(name()).isEqualTo("Gate test");

        AtomicBoolean rollbackOnly = new AtomicBoolean(true);
        List<Object> refusals = new TransactionTemplate(transactions).execute(transaction -> {
            List<Object> outcomes = List.of(caught(posting), caught(UUID.randomUUID()));
            rollbackOnly.set(transaction.isRollbackOnly());
            rename("Refused twice");
            return outcomes;
        });
        assertThat(rollbackOnly).isFalse();
        assertExpired(refusals.get(0));
        assertNotFound(refusals.get(1));
        assertThat(name()).isEqualTo("Refused twice");
    }

    private void rename(String name) {
        assertThat(jdbc.update("UPDATE user_accounts SET full_name = ? WHERE email = 'head@example.test'", name))
                .isEqualTo(1);
    }

    private String name() {
        return jdbc.queryForObject("SELECT full_name FROM user_accounts WHERE email = 'head@example.test'",
                String.class);
    }

    // The gate called directly inside the caller's transaction: the posting it returns, or the ApiException it throws.
    private Object caught(UUID id) {
        try {
            return gate.requireOpen(id);
        } catch (ApiException exception) {
            return exception;
        }
    }

    private JsonNode detail() throws Exception {
        var response = postings.publicDetail(posting);
        postings.noStore(response);
        return api.expect(response, 200);
    }

    // The gate called the way the apply API will call it: first, in a new READ COMMITTED read-write transaction.
    private OpenJobPosting apply(UUID id) {
        return new TransactionTemplate(transactions).execute(transaction -> gate.requireOpen(id));
    }

    // The posting the gate returns, or the ApiException it throws.
    private Object outcome(UUID id) {
        try {
            return apply(id);
        } catch (ApiException exception) {
            return exception;
        }
    }

    private void expired(UUID id) {
        assertExpired(outcome(id));
    }

    private void notFound(UUID id) {
        assertNotFound(outcome(id));
    }

    private static void assertExpired(Object outcome) {
        assertRefused(outcome, HttpStatus.CONFLICT, "JOB_POSTING_EXPIRED", EXPIRED_MESSAGE);
    }

    private static void assertNotFound(Object outcome) {
        assertRefused(outcome, HttpStatus.NOT_FOUND, "JOB_POSTING_NOT_FOUND", NOT_FOUND_MESSAGE);
    }

    private static void assertRefused(Object outcome, HttpStatus status, String code, String message) {
        assertThat(outcome).isInstanceOfSatisfying(ApiException.class, exception -> {
            assertThat(exception.getStatus()).isEqualTo(status);
            assertThat(exception.getCode()).isEqualTo(code);
            assertThat(exception.getMessage()).isEqualTo(message);
            assertThat(exception.getFieldErrors()).isEmpty();
        });
    }

    private void assertMisused(TransactionTemplate template) {
        assertThatThrownBy(() -> template.execute(transaction -> gate.requireOpen(posting)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("JobPostingApplicationGate.requireOpen needs a read-write READ COMMITTED transaction");
    }

    private Map<String, List<Map<String, Object>>> snapshot() {
        return Map.of(
                "postings", jdbc.queryForList("SELECT xmin::text AS version, * FROM job_postings ORDER BY id"),
                "events", jdbc.queryForList("SELECT xmin::text AS version, * FROM job_posting_events "
                        + "ORDER BY job_posting_id, revision"),
                "authors", jdbc.queryForList("SELECT xmin::text AS version, * FROM job_posting_authors "
                        + "ORDER BY job_posting_id, account_id"),
                "requisitions", jdbc.queryForList("SELECT xmin::text AS version, * FROM recruitment_requisitions "
                        + "ORDER BY id"),
                "requisitionEvents", jdbc.queryForList("SELECT xmin::text AS version, * FROM "
                        + "requisition_workflow_events ORDER BY requisition_id, revision"));
    }

    private UUID account(String email, Role... roles) {
        return accounts.saveAndFlush(new Account(email, "Gate test", passwordHash, Set.of(roles), START)).getId();
    }

    private static int lock(Connection connection, UUID id) throws Exception {
        try (var statement = connection.prepareStatement(HOLD_REQUISITION)) {
            statement.setObject(1, id);
            try (var row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getInt(1);
            }
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
