package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.scheduling.support.SimpleTriggerContext;
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
import vn.ttcs.recruitment.jobposting.JobPostingExpiry;
import vn.ttcs.recruitment.jobposting.JobPostingRepository;
import vn.ttcs.recruitment.jobposting.JobPostingStatus;
import vn.ttcs.recruitment.requisition.RequisitionRecruiterRole;
import vn.ttcs.recruitment.requisition.RequisitionStatus;

import javax.sql.DataSource;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
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

// Task 356 (story S4-08): "Tạo tác vụ hàng ngày tự đánh dấu tin hết hạn". Every day at 00:05 in Vietnam the task moves
// each PUBLISHED posting whose deadline has passed to EXPIRED, with an EXPIRED step that has no actor. It runs again
// without harm, catches up missed days, never fights a take-down (task 355), a closure (task 292) or an application
// (task 321), and changes nothing candidates see. The task is called here the way the scheduler calls it
// (JobPostingExpiry.expireDue), with the test clock. The cron of this context names 31 Feb, a day that never comes:
// the scheduler is on and the task is registered, but no run is ever queued, so the task never runs by itself. Not
// a cron that fires once in years: the scheduler waits on the wall clock while the run reads the test clock, so a
// run that started while this class runs would expire the postings a test is about to expire itself. Races are
// staged like JobPostingUnpublishLockIntegrationTest: a raw connection holds a row, the callers queue behind it in
// arrival order (pg_blocking_pids), then the row is released.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn",
        "app.job-posting-expiry.cron=0 0 0 31 2 *"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class JobPostingExpiryIntegrationTest {
    // 10:00 on 11 Oct 2026 in Vietnam, when the postings are published.
    private static final Instant START = Instant.parse("2026-10-11T03:00:00Z");
    // 23:59:59 on 30 Nov 2026 in Vietnam, the last second of the deadline day; then 00:00 and 00:05 on 1 Dec.
    private static final Instant LAST_SECOND = Instant.parse("2026-11-30T16:59:59Z");
    private static final Instant MIDNIGHT = Instant.parse(JobPostingTestSupport.CLOSES_AT);
    private static final Instant RUN = Instant.parse("2026-11-30T17:05:00.123456789Z");
    private static final Instant RUN_STORED = Instant.parse("2026-11-30T17:05:00.123456Z");
    private static final String HOLD_REQUISITION =
            "SELECT pg_backend_pid() FROM recruitment_requisitions WHERE id = ? FOR NO KEY UPDATE";
    private static final String NOT_EXPIRED_LOG = "was not expired; the next run retries it";
    // PostgreSQL's error for the side of a deadlock it aborts, which the run or the request logs.
    private static final String DEADLOCK_LOG = "deadlock detected";
    private static final List<String> HISTORY_KEYS = List.of("id", "revision", "action", "statusBefore",
            "statusAfter", "actorId", "actorName", "occurredAt", "comment");
    private static final List<String> FLAGS = List.of("canEdit", "canSubmit", "canApprove", "canRequestRevision",
            "canPublish", "canUnpublish");

    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private AccountRepository accounts;
    @Autowired private BootstrapAdmin bootstrap;
    @Autowired private AuthIntegrationTest.MutableClock clock;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JobPostingExpiry expiry;
    @Autowired private JobPostingRepository postingRepository;
    @Autowired private JobPostingApplicationGate gate;
    @Autowired private List<ScheduledTaskHolder> schedules;

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
    private UUID headId;
    private UUID department;
    private UUID position;
    private UUID workLocation;
    private UUID employmentType;

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
        adminId = jdbc.queryForObject("SELECT id FROM user_accounts WHERE email = 'admin@example.test'", UUID.class);
        passwordHash = accounts.findById(adminId).orElseThrow().getPasswordHash();
        adminToken = api.token("admin@example.test");
        hrId = account("hr@example.test", "Nguyễn Thị Hoa", Role.HR_MANAGER);
        hrToken = api.token("hr@example.test");
        headId = account("head@example.test", "Trưởng phòng IT", Role.HIRING_MANAGER);
        recruiterId = account("recruiter@example.test", "Trần Văn Tuyển", Role.RECRUITER);
        recruiterToken = api.token("recruiter@example.test");
        department = api.department("IT", headId, null, START);
        position = api.position(START);
        workLocation = postings.catalogValue(WORK_LOCATION, "HN", "Hà Nội", true, START);
        employmentType = postings.catalogValue(EMPLOYMENT_TYPE, "FULL_TIME", "Toàn thời gian", true, START);
    }

    // The scheduler is on (SchedulingConfiguration) and holds exactly one expiry task, the daily run that
    // JobPostingExpirySchedule registers, with the cron of the settings in the business zone (CronTrigger.equals
    // compares both). This context's cron never matches a date: the trigger has no next run, so the scheduler has
    // queued nothing. Run by hand, the task is the daily run: it logs its count.
    @Test
    void theDailyRunIsScheduledInTheBusinessZone(CapturedOutput output) {
        List<ScheduledTask> tasks = new ArrayList<>();
        schedules.forEach(holder -> tasks.addAll(holder.getScheduledTasks()));

        assertThat(tasks).filteredOn(task -> task.getTask().getRunnable().toString()
                .equals("vn.ttcs.recruitment.jobposting.JobPostingExpiry.runDaily"))
                .singleElement().satisfies(task -> {
            assertThat(task.nextExecution()).isNull();
            assertThat(task.getTask()).isInstanceOfSatisfying(CronTask.class, cron -> {
                assertThat(cron.getExpression()).isEqualTo("0 0 0 31 2 *");
                assertThat(cron.getTrigger()).as("the cron in the business zone")
                        .isEqualTo(new CronTrigger(cron.getExpression(), ZoneId.of("Asia/Ho_Chi_Minh")));
                assertThat(cron.getRunnable().toString())
                        .isEqualTo("vn.ttcs.recruitment.jobposting.JobPostingExpiry.runDaily");
                var server = new SimpleTriggerContext(Clock.fixed(START, ZoneOffset.UTC));
                assertThat(((CronTrigger) cron.getTrigger()).nextExecution(server)).isNull();
                assertThat(output).doesNotContain("Job posting expiry: 0 expired");
                cron.getRunnable().run();
                assertThat(output).contains("Job posting expiry: 0 expired");
            });
        });
    }

    // S4-08 AC2: "Hệ thống tự động ẩn tin quá hạn nộp hồ sơ thông qua tác vụ chạy định kỳ hằng ngày". Only published
    // postings whose deadline day has passed in Vietnam move, a paused requisition's included; each gets one EXPIRED
    // step without an actor or a comment at the server time, a new revision, and keeps its content, its publication
    // trail and updatedAt. A run at 23:59:59 on the deadline day, or a call for one of its postings then, moves
    // nothing; a second run moves nothing more.
    @Test
    void onlyPublishedPostingsPastTheirDeadlineExpireOnce() throws Exception {
        UUID due = published(requisition(department), "2026-11-30");
        UUID tomorrow = published(requisition(department), "2026-12-01");
        UUID approved = approved(requisition(department));
        UUID takenDown = published(requisition(department), "2026-11-30");
        ok(postings.unpublish(takenDown, 4, "Đã nhận đủ hồ sơ.", recruiterToken));
        UUID filledRequisition = requisition(department);
        UUID closed = published(filledRequisition, "2026-11-30");
        status.ok(status.change(filledRequisition, "FILLED", "Đã tuyển đủ", 0, hrToken));
        UUID pausedRequisition = requisition(department);
        UUID paused = published(pausedRequisition, "2026-11-30");
        status.ok(status.change(pausedRequisition, "PAUSED", "Chờ ngân sách quý 4", 0, hrToken));
        Map<UUID, Map<String, Object>> rowsBefore = rows();
        List<Map<String, Object>> eventsBefore = allEvents();

        clock.set(LAST_SECOND);
        // On the deadline day itself (30 Nov in Vietnam) no posting is a candidate yet.
        assertThat(postingRepository.findIdsToExpire(JobPostingStatus.PUBLISHED, LocalDate.of(2026, 11, 30)))
                .isEmpty();
        assertThat(expiry.expireDue()).isZero();
        // Asked for that posting alone, the check under the locks still finds its deadline day not over.
        assertThat(expiry.expire(due)).isFalse();
        assertThat(rows()).isEqualTo(rowsBefore);
        assertThat(allEvents()).isEqualTo(eventsBefore);

        clock.set(RUN);
        assertThat(expiry.expireDue()).isEqualTo(2);

        Map<UUID, Map<String, Object>> rowsAfter = rows();
        for (UUID expired : List.of(due, paused)) {
            Map<String, Object> row = rowsAfter.get(expired);
            assertThat(row.get("status")).isEqualTo("EXPIRED");
            assertThat(row.get("revision")).isEqualTo(5);
            assertThat(withoutStatus(row)).isEqualTo(withoutStatus(rowsBefore.get(expired)));
            assertThat(row.get("published_by")).isEqualTo(hrId);
            List<Map<String, Object>> steps = postings.events(expired);
            assertThat(steps).extracting(step -> step.get("event_type"))
                    .containsExactly("SUBMITTED", "APPROVED", "PUBLISHED", "EXPIRED");
            assertThat(steps.getLast()).containsEntry("revision", 5).containsEntry("status_before", "PUBLISHED")
                    .containsEntry("status_after", "EXPIRED").containsEntry("actor_id", null)
                    .containsEntry("comment", null);
            assertThat(((Timestamp) steps.getLast().get("occurred_at")).toInstant()).isEqualTo(RUN_STORED);
        }
        for (UUID untouched : List.of(tomorrow, approved, takenDown, closed)) {
            assertThat(rowsAfter.get(untouched)).isEqualTo(rowsBefore.get(untouched));
        }
        assertThat(allEvents()).hasSize(eventsBefore.size() + 2);
        // The requisitions are never written.
        assertThat(status.row(pausedRequisition).get("status")).isEqualTo("PAUSED");

        // Idempotent: the same run again, and later the same day, change nothing.
        Map<UUID, Map<String, Object>> rowsOnce = rows();
        List<Map<String, Object>> eventsOnce = allEvents();
        assertThat(expiry.expireDue()).isZero();
        clock.set(RUN.plusSeconds(3_600));
        assertThat(expiry.expireDue()).isZero();
        for (UUID expired : List.of(due, paused)) {
            assertThat(expiry.expire(expired)).isFalse();
        }
        assertThat(expiry.expire(UUID.randomUUID())).isFalse();
        assertThat(rows()).isEqualTo(rowsOnce);
        assertThat(allEvents()).isEqualTo(eventsOnce);
    }

    // A day without a run is caught up: the next run expires every posting whose deadline has passed since, and none
    // whose deadline is today.
    @Test
    void aMissedRunIsCaughtUpByTheNextOne() throws Exception {
        List<UUID> due = new ArrayList<>();
        for (String deadline : List.of("2026-11-30", "2026-12-01", "2026-12-02")) {
            due.add(published(requisition(department), deadline));
        }
        UUID today = published(requisition(department), "2026-12-04");

        // 00:05 on 4 Dec 2026 in Vietnam, three days after the first deadline passed. The candidates are the postings
        // whose deadline is before that business date, the oldest deadline first; the one due today is not among them.
        clock.set(Instant.parse("2026-12-03T17:05:00Z"));
        assertThat(postingRepository.findIdsToExpire(JobPostingStatus.PUBLISHED, LocalDate.of(2026, 12, 4)))
                .containsExactlyElementsOf(due);
        assertThat(expiry.expireDue()).isEqualTo(3);

        for (UUID posting : due) {
            assertThat(postings.row(posting).get("status")).isEqualTo("EXPIRED");
        }
        assertThat(postings.row(today).get("status")).isEqualTo("PUBLISHED");
        assertThat(expiry.expireDue()).isZero();
    }

    // Two runs at the same moment (two servers, or a run started by hand during the scheduled one) queue on the same
    // row locks: each posting is expired by exactly one of them, the other finds it EXPIRED after the wait and skips
    // it, and nothing fails. The first posting's requisition is held so that both runs have read the same list and wait
    // on the same row.
    @Test
    void twoRunsAtOnceExpireEachPostingOnce(CapturedOutput output) throws Exception {
        UUID firstRequisition = requisition(department);
        UUID first = published(firstRequisition, "2026-11-29");
        List<UUID> all = new ArrayList<>(List.of(first));
        for (int index = 0; index < 3; index++) {
            all.add(published(requisition(department), "2026-11-30"));
        }
        clock.set(RUN);

        List<Integer> counts = new ArrayList<>();
        try (var connection = dataSource.getConnection(); var executor = Executors.newFixedThreadPool(2)) {
            connection.setAutoCommit(false);
            int blocker = lock(connection, firstRequisition);
            Future<Integer> one = executor.submit(() -> expiry.expireDue());
            Future<Integer> two = executor.submit(() -> expiry.expireDue());
            awaitWaiters(blocker, 2);
            assertThat(one.isDone()).isFalse();
            assertThat(two.isDone()).isFalse();
            connection.commit();
            counts.add(one.get(30, TimeUnit.SECONDS));
            counts.add(two.get(30, TimeUnit.SECONDS));
        }

        assertThat(counts.get(0) + counts.get(1)).isEqualTo(all.size());
        for (UUID posting : all) {
            assertThat(postings.row(posting).get("status")).isEqualTo("EXPIRED");
            assertThat(postings.events(posting).stream().filter(step -> step.get("event_type").equals("EXPIRED")))
                    .hasSize(1);
        }
        // The loser of each race skipped the posting after its re-check; it never failed and was never logged.
        expiry.runDaily();
        assertThat(output.getAll()).contains("Job posting expiry: 0 expired").doesNotContain(NOT_EXPIRED_LOG);
    }

    // One posting that cannot be expired (here an EXPIRED step already written by hand, which
    // job_posting_events_one_expiry_idx refuses a second time) is logged and stays as it was; the others still expire.
    @Test
    void aPostingThatFailsIsLoggedAndTheOthersStillExpire(CapturedOutput output) throws Exception {
        UUID broken = published(requisition(department), "2026-11-30");
        UUID other = published(requisition(department), "2026-11-30");
        jdbc.update("""
                INSERT INTO job_posting_events (id, job_posting_id, revision, event_type, status_before, status_after,
                    actor_id, occurred_at, comment)
                VALUES (?, ?, 9, 'EXPIRED', 'PUBLISHED', 'EXPIRED', NULL, ?, NULL)
                """, UUID.randomUUID(), broken, Timestamp.from(START));
        Map<String, Object> brokenBefore = postings.row(broken);
        clock.set(RUN);

        assertThat(expiry.expireDue()).isOne();

        assertThat(postings.row(other).get("status")).isEqualTo("EXPIRED");
        assertThat(postings.row(broken)).isEqualTo(brokenBefore);
        assertThat(output.getAll()).contains("Job posting " + broken + " " + NOT_EXPIRED_LOG)
                .contains("job_posting_events_one_expiry_idx");
    }

    // S4-08 with task 355: a take-down and the run on the same posting, in both orders. Taken down first, the run
    // skips it. Expired first, a take-down sent on the old view is refused (the expiry made a new revision) and one
    // sent on the new revision takes the expired posting down. Both lock the requisition before the posting, so the
    // second one only waits for the first: no deadlock and no failed run is logged. A run that locked the posting
    // first would deadlock with a take-down queued before it, and whichever side PostgreSQL aborts shows: a failed
    // run in the log, a failed take-down as a 500 (and in the log).
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aTakeDownAndTheRun(boolean takenDownFirst, CapturedOutput output) throws Exception {
        UUID requisition = requisition(department);
        UUID posting = published(requisition, "2026-11-30");
        clock.set(RUN);
        String lateRecruiter = api.token("recruiter@example.test");
        Callable<Object> takeDown = () -> postings.unpublish(posting, 4, "Gỡ tin đã hết hạn.", lateRecruiter);
        Callable<Object> run = () -> expiry.expireDue();

        List<Object> outcomes = staged(requisition, takenDownFirst ? takeDown : run, takenDownFirst ? run : takeDown);

        assertThat(output.getAll()).doesNotContain(NOT_EXPIRED_LOG).doesNotContain(DEADLOCK_LOG);
        @SuppressWarnings("unchecked")
        var response = (HttpResponse<String>) outcomes.get(takenDownFirst ? 0 : 1);
        int expired = (Integer) outcomes.get(takenDownFirst ? 1 : 0);
        if (takenDownFirst) {
            assertThat(api.expect(response, 200).path("status").asText()).isEqualTo("UNPUBLISHED");
            assertThat(expired).isZero();
            assertThat(postings.events(posting)).extracting(step -> step.get("event_type"))
                    .containsExactly("SUBMITTED", "APPROVED", "PUBLISHED", "UNPUBLISHED");
            return;
        }
        assertThat(expired).isOne();
        postings.error(response, 409, "JOB_POSTING_CHANGED");
        assertThat(postings.row(posting).get("status")).isEqualTo("EXPIRED");
        JsonNode view = ok(postings.get(posting, lateRecruiter));
        assertThat(view.path("status").asText()).isEqualTo("EXPIRED");
        assertThat(view.path("actions").path("canUnpublish").asBoolean()).isTrue();

        assertThat(ok(postings.unpublish(posting, 5, "Gỡ tin đã hết hạn.", lateRecruiter)).path("status").asText())
                .isEqualTo("UNPUBLISHED");
        assertThat(postings.events(posting).getLast()).containsEntry("event_type", "UNPUBLISHED")
                .containsEntry("status_before", "EXPIRED").containsEntry("status_after", "UNPUBLISHED")
                .containsEntry("actor_id", recruiterId);
        postings.error(postings.publicDetail(posting), 404, "JOB_POSTING_NOT_FOUND");
    }

    // S4-08 with task 292: the requisition filled and the run, in both orders. Expired first, the closure closes the
    // posting from EXPIRED; filled first, the run skips the closed posting. As with the take-down, no deadlock and no
    // failed run is logged: a run that locked the posting first would deadlock with a closure queued before it.
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void theClosureOfTheRequisitionAndTheRun(boolean expiredFirst, CapturedOutput output) throws Exception {
        UUID requisition = requisition(department);
        UUID posting = published(requisition, "2026-11-30");
        clock.set(RUN);
        String lateHr = api.token("hr@example.test");
        Callable<Object> fill = () -> status.change(requisition, "FILLED", "Đã tuyển đủ", 0, lateHr);
        Callable<Object> run = () -> expiry.expireDue();

        List<Object> outcomes = staged(requisition, expiredFirst ? run : fill, expiredFirst ? fill : run);

        assertThat(output.getAll()).doesNotContain(NOT_EXPIRED_LOG).doesNotContain(DEADLOCK_LOG);
        @SuppressWarnings("unchecked")
        var response = (HttpResponse<String>) outcomes.get(expiredFirst ? 1 : 0);
        assertThat(status.ok(response).path("status").asText()).isEqualTo("FILLED");
        assertThat(outcomes.get(expiredFirst ? 0 : 1)).isEqualTo(expiredFirst ? 1 : 0);
        assertThat(postings.row(posting).get("status")).isEqualTo("CLOSED");
        List<Map<String, Object>> steps = postings.events(posting);
        assertThat(steps).extracting(step -> step.get("event_type")).containsExactlyElementsOf(expiredFirst
                ? List.of("SUBMITTED", "APPROVED", "PUBLISHED", "EXPIRED", "CLOSED")
                : List.of("SUBMITTED", "APPROVED", "PUBLISHED", "CLOSED"));
        assertThat(steps.getLast()).containsEntry("status_before", expiredFirst ? "EXPIRED" : "PUBLISHED")
                .containsEntry("comment", "Đã tuyển đủ");
    }

    // With task 321: an application in progress holds the requisition (FOR NO KEY UPDATE) and the posting (FOR SHARE),
    // so the run waits for it and then expires the posting, and an application that arrives while the run waits
    // queues behind the run, not beside the first application (waiters are served in arrival order; a share lock on
    // the requisition would let it through at once, and a steady stream of applications could hold the run back); a
    // run in progress makes an application wait, which then gets the closed notice (409 JOB_POSTING_EXPIRED) from the
    // posting's new status.
    @Test
    void theRunAndAnApplicationWaitForEachOther() throws Exception {
        UUID requisition = requisition(department);
        UUID posting = published(requisition, "2026-11-30");
        UUID otherRequisition = requisition(department);
        UUID other = published(otherRequisition, "2026-11-30");
        clock.set(RUN);
        CountDownLatch checked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger applicant = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(3)) {
            // The application is refused (the deadline has passed), but its locks last until it commits.
            Future<Object> application = executor.submit(() -> new TransactionTemplate(transactions)
                    .execute(transaction -> {
                        Object outcome = caught(posting);
                        applicant.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                        checked.countDown();
                        await(release);
                        return outcome;
                    }));
            assertThat(checked.await(20, TimeUnit.SECONDS)).isTrue();
            // This posting only: the other one is raced below.
            Future<Boolean> run = executor.submit(() -> expiry.expire(posting));
            awaitWaiters(applicant.get(), 1);
            Future<Object> later = executor.submit(() -> outcome(posting));
            awaitWaiters(applicant.get(), 2);
            assertThat(run.isDone()).isFalse();
            assertThat(later.isDone()).isFalse();
            assertThat(postings.row(posting).get("status")).isEqualTo("PUBLISHED");

            release.countDown();
            assertExpiredNotice(application.get(20, TimeUnit.SECONDS));
            assertThat(run.get(20, TimeUnit.SECONDS)).isTrue();
            assertExpiredNotice(later.get(20, TimeUnit.SECONDS));
        }
        assertThat(postings.row(posting).get("status")).isEqualTo("EXPIRED");

        List<Object> outcomes = staged(otherRequisition, () -> expiry.expireDue(), () -> outcome(other));
        assertThat(outcomes.get(0)).isEqualTo(1);
        assertExpiredNotice(outcomes.get(1));
        assertThat(postings.row(other).get("status")).isEqualTo("EXPIRED");
    }

    // S4-08 AC2 for candidates: the list hid the posting from 00:00 by its date (task 309) and its detail already
    // showed the closed notice (task 320), so the run changes no public answer, byte for byte. The posting is never
    // published again; its authors see it as EXPIRED and may still take it down.
    @Test
    void candidatesSeeNothingChange() throws Exception {
        UUID posting = published(requisition(department), "2026-11-30");
        UUID open = published(requisition(api.department("QA", headId, null, START)), "2026-12-01");
        clock.set(RUN);
        String detailBefore = publicBody(postings.publicDetail(posting));
        String listBefore = publicBody(postings.publicList(0, 20));
        String filtersBefore = publicBody(postings.publicFilters());
        JobPostingTestSupport.assertApplicationState(json.readTree(detailBefore), false);
        assertThat(json.readTree(listBefore).path("items")).singleElement()
                .satisfies(item -> assertThat(item.path("id").asText()).isEqualTo(open.toString()));

        assertThat(expiry.expireDue()).isOne();

        assertThat(postings.row(posting).get("status")).isEqualTo("EXPIRED");
        assertThat(publicBody(postings.publicDetail(posting))).isEqualTo(detailBefore);
        assertThat(publicBody(postings.publicList(0, 20))).isEqualTo(listBefore);
        assertThat(publicBody(postings.publicFilters())).isEqualTo(filtersBefore);
        assertExpiredNotice(outcome(posting));

        String lateHr = api.token("hr@example.test");
        JsonNode again = postings.error(postings.publish(posting, 5, lateHr), 409, "JOB_POSTING_ALREADY_PUBLISHED");
        assertThat(again.path("message").asText()).isEqualTo("Tin tuyển dụng đã được xuất bản.");
        JsonNode view = ok(postings.get(posting, lateHr));
        assertThat(view.path("status").asText()).isEqualTo("EXPIRED");
        assertThat(view.path("revision").asInt()).isEqualTo(5);
        assertThat(new ArrayList<>(view.path("actions").propertyNames())).containsExactlyElementsOf(FLAGS);
        for (String flag : FLAGS) {
            assertThat(view.path("actions").path(flag).asBoolean()).as(flag).isEqualTo(flag.equals("canUnpublish"));
        }
        List<String> expired = new ArrayList<>();
        ok(postings.list("?status=EXPIRED", lateHr)).path("items")
                .forEach(item -> expired.add(item.path("id").asText()));
        assertThat(expired).containsExactly(posting.toString());
    }

    // The history shows the expiry as a step without an actor (the frontend shows "Hệ thống"); the earlier steps keep
    // their actors.
    @Test
    void theHistoryShowsTheExpiryWithoutAnActor() throws Exception {
        UUID posting = published(requisition(department), "2026-11-30");
        clock.set(RUN);
        assertThat(expiry.expireDue()).isOne();

        JsonNode history = ok(postings.history(posting, api.token("recruiter@example.test")));

        assertThat(history.size()).isEqualTo(4);
        JsonNode top = history.path(0);
        assertThat(new ArrayList<>(top.propertyNames())).containsExactlyElementsOf(HISTORY_KEYS);
        assertThat(top.path("revision").asInt()).isEqualTo(5);
        assertThat(top.path("action").asText()).isEqualTo("EXPIRED");
        assertThat(top.path("statusBefore").asText()).isEqualTo("PUBLISHED");
        assertThat(top.path("statusAfter").asText()).isEqualTo("EXPIRED");
        assertThat(top.path("actorId").isNull()).isTrue();
        assertThat(top.path("actorName").isNull()).isTrue();
        assertThat(top.path("occurredAt").asText()).isEqualTo("2026-11-30T17:05:00.123456Z");
        assertThat(top.path("comment").isNull()).isTrue();
        List<String> actors = new ArrayList<>();
        for (int index = 1; index < history.size(); index++) {
            actors.add(history.path(index).path("action").asText() + " " + history.path(index).path("actorName")
                    .asText());
        }
        String adminName = jdbc.queryForObject("SELECT full_name FROM user_accounts WHERE id = ?", String.class,
                adminId);
        assertThat(actors).containsExactly("PUBLISHED Nguyễn Thị Hoa", "APPROVED " + adminName,
                "SUBMITTED Nguyễn Thị Hoa");
        assertThat(history.path(1).path("actorId").asText()).isEqualTo(hrId.toString());
    }

    // Today is read after the locks: a run that waits for a requisition write from 23:59:59 on the deadline day until
    // after midnight decides on the new date and expires the posting, at the time it really did.
    @Test
    void aRunThatWaitsPastMidnightUsesTheNewDate() throws Exception {
        UUID requisition = requisition(department);
        UUID posting = published(requisition, "2026-11-30");
        clock.set(LAST_SECOND);
        try (var connection = dataSource.getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            int blocker = lock(connection, requisition);
            Future<Boolean> expired = executor.submit(() -> expiry.expire(posting));
            awaitWaiters(blocker, 1);
            clock.set(MIDNIGHT);
            connection.commit();
            assertThat(expired.get(20, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(postings.row(posting).get("status")).isEqualTo("EXPIRED");
        assertThat(postings.events(posting).stream().filter(step -> step.get("event_type").equals("EXPIRED")))
                .singleElement().satisfies(step -> assertThat(((Timestamp) step.get("occurred_at")).toInstant())
                        .isEqualTo(MIDNIGHT));
    }

    // A posting of the requisition published through the API with this deadline: HR writes, sends and publishes it,
    // ADMIN approves it (revision 4).
    private UUID published(UUID requisition, String deadline) throws Exception {
        UUID id = approved(requisition, deadline);
        ok(postings.publish(id, 3, hrToken));
        return id;
    }

    private UUID approved(UUID requisition) throws Exception {
        return approved(requisition, JobPostingTestSupport.DEADLINE);
    }

    private UUID approved(UUID requisition, String deadline) throws Exception {
        Map<String, Object> body = postings.body(requisition, workLocation, employmentType);
        body.put("applicationDeadline", deadline);
        UUID id = UUID.fromString(postings.created(body, hrToken).path("id").asText());
        ok(postings.submit(id, 1, null, hrToken));
        ok(postings.decide(id, "APPROVE", null, 2, adminToken));
        return id;
    }

    // An approved requisition of the department written with SQL, without history (revision 0), whose primary
    // recruiter is the recruiter.
    private UUID requisition(UUID department) {
        UUID requisition = postings.requisition(position, department, adminId, RequisitionStatus.APPROVED, START);
        postings.assign(requisition, recruiterId, RequisitionRecruiterRole.PRIMARY, hrId, START);
        return requisition;
    }

    // Holds the requisition, starts first and waits until it waits for the row, then starts second and waits for it
    // too, and releases the row: first runs, then second. Returns what each returned.
    private List<Object> staged(UUID requisition, Callable<Object> first, Callable<Object> second) throws Exception {
        List<Object> results = new ArrayList<>();
        try (var connection = dataSource.getConnection(); var executor = Executors.newFixedThreadPool(2)) {
            connection.setAutoCommit(false);
            int blocker = lock(connection, requisition);
            Future<Object> firstResult = executor.submit(first);
            awaitWaiters(blocker, 1);
            Future<Object> secondResult = executor.submit(second);
            awaitWaiters(blocker, 2);
            connection.commit();
            results.add(firstResult.get(20, TimeUnit.SECONDS));
            results.add(secondResult.get(20, TimeUnit.SECONDS));
        }
        return results;
    }

    // The gate the apply API (task 326) calls, in its own transaction: the posting it returns or its refusal.
    private Object outcome(UUID posting) {
        try {
            return new TransactionTemplate(transactions).execute(transaction -> gate.requireOpen(posting));
        } catch (ApiException refusal) {
            return refusal;
        }
    }

    // The gate called inside the caller's transaction, which stays free to commit after a refusal.
    private Object caught(UUID posting) {
        try {
            return gate.requireOpen(posting);
        } catch (ApiException refusal) {
            return refusal;
        }
    }

    private static void assertExpiredNotice(Object outcome) {
        assertThat(outcome).isInstanceOfSatisfying(ApiException.class, refusal -> {
            assertThat(refusal.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(refusal.getCode()).isEqualTo("JOB_POSTING_EXPIRED");
            assertThat(refusal.getMessage()).isEqualTo("Tin tuyển dụng đã hết hạn nộp hồ sơ.");
        });
    }

    // A public answer (200, no-store) as the exact text candidates receive.
    private String publicBody(HttpResponse<String> response) {
        api.expect(response, 200);
        postings.noStore(response);
        return response.body();
    }

    private Map<UUID, Map<String, Object>> rows() {
        Map<UUID, Map<String, Object>> rows = new LinkedHashMap<>();
        jdbc.queryForList("SELECT * FROM job_postings ORDER BY id").forEach(row -> rows.put((UUID) row.get("id"), row));
        return rows;
    }

    private List<Map<String, Object>> allEvents() {
        return jdbc.queryForList("SELECT * FROM job_posting_events ORDER BY job_posting_id, revision");
    }

    // The row without what the expiry changes.
    private static Map<String, Object> withoutStatus(Map<String, Object> row) {
        Map<String, Object> copy = new LinkedHashMap<>(row);
        copy.remove("status");
        copy.remove("revision");
        return copy;
    }

    private JsonNode ok(HttpResponse<String> response) {
        JsonNode body = api.expect(response, 200);
        postings.noStore(response);
        return body;
    }

    private UUID account(String email, String fullName, Role role) {
        return accounts.saveAndFlush(new Account(email, fullName, passwordHash, Set.of(role), START)).getId();
    }

    private static int lock(Connection connection, UUID requisition) throws Exception {
        try (var statement = connection.prepareStatement(HOLD_REQUISITION)) {
            statement.setObject(1, requisition);
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
        assertThat(observed).as("the callers must wait for the blocker's locks").isGreaterThanOrEqualTo(expected);
    }
}
