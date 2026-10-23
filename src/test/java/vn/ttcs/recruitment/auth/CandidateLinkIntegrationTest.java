package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.application.ApplicationStorageService;
import vn.ttcs.recruitment.application.ApplicationSubmission;
import vn.ttcs.recruitment.application.CvValidator;
import vn.ttcs.recruitment.application.DuplicateApplicationException;
import vn.ttcs.recruitment.application.PostgresApplicationReceiptWriter;
import vn.ttcs.recruitment.common.ApiException;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 360 (story S4-09, "Một hồ sơ ứng viên liên kết nhiều lần ứng tuyển"): TRAN DUC LUONG's
 * ApplicationStorageService.store links every application to the one candidate profile of its normalized email (V32),
 * in the application's own transaction, after the duplicate arbiter. Real PostgreSQL, no HTTP; the confirmation worker
 * never runs (poll delay of an hour), so the receipt writer case sends no mail.
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
class CandidateLinkIntegrationTest {
    private static final Instant LATER = ApplicationTestSupport.NOW.plus(Duration.ofHours(2));

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private Environment environment;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ApplicationStorageService storage;
    @Autowired private CvValidator validator;
    @Autowired private PostgresApplicationReceiptWriter receipts;
    @Autowired private AuthIntegrationTest.MutableClock clock;

    private ApplicationTestSupport applications;
    private UUID posting;
    private UUID otherPosting;

    @BeforeEach
    void fixture() {
        clock.set(ApplicationTestSupport.NOW);
        int port = Integer.parseInt(environment.getRequiredProperty("local.server.port"));
        applications = new ApplicationTestSupport(jdbc, json, storage, validator, port);
        applications.reset();
        new JobPostingTestSupport(jdbc, json, transactions, port).reset();
        // Two postings of two requisitions: one email may apply to both (task 327 reserves it per requisition only).
        posting = applications.publishedPosting("Lập trình viên Java");
        otherPosting = applications.publishedPosting("Kiểm thử phần mềm");
    }

    // Invisible edges and letter case do not make another person: " A@EXAMPLE.test " between no-break spaces and
    // "a@example.test" are one profile, created at the first application. Each application keeps its own snapshot.
    @Test
    void everyApplicationOfOneNormalizedEmailIsLinkedToOneProfile() {
        String nbsp = Character.toString(0x00A0);
        var first = applications.stored(posting, nbsp + " A@EXAMPLE.test " + nbsp);
        clock.set(LATER);
        var second = applications.stored(otherPosting, "a@example.test");
        var other = applications.stored(posting, "b@example.test");

        assertThat(second.candidateId()).isEqualTo(first.candidateId());
        assertThat(other.candidateId()).isNotEqualTo(first.candidateId());
        assertThat(count("candidates")).isEqualTo(2);
        assertThat(count("candidate_applications")).isEqualTo(3);
        assertThat(jdbc.queryForMap("SELECT normalized_email, created_at FROM candidates WHERE id = ?", first.candidateId()))
                .containsEntry("normalized_email", "a@example.test")
                .containsEntry("created_at", Timestamp.from(ApplicationTestSupport.NOW));
        assertThat(jdbc.queryForObject("SELECT linked_at FROM candidate_applications WHERE application_id = ?",
                Timestamp.class, second.applicationId())).isEqualTo(Timestamp.from(LATER));
        assertThat(jdbc.queryForObject("SELECT email FROM job_applications WHERE id = ?", String.class,
                first.applicationId())).isEqualTo("A@EXAMPLE.test");
        assertThat(jdbc.queryForObject("SELECT email FROM job_applications WHERE id = ?", String.class,
                second.applicationId())).isEqualTo("a@example.test");
    }

    // The arbiter refuses the duplicate before any link: the 409 adds neither a link nor a profile. An application
    // written with SQL (it has no profile) shows that the refused one does not create the missing profile either.
    @Test
    void aDuplicateLinksNothingAndCreatesNoProfile() {
        var stored = applications.stored(posting, "dup@example.test");
        assertThatThrownBy(() -> applications.stored(posting, " DUP@example.test "))
                .isInstanceOf(DuplicateApplicationException.class);
        assertThat(count("candidates")).isEqualTo(1);
        assertThat(count("candidate_applications")).isEqualTo(1);
        assertThat(applications.candidateOf(stored.applicationId())).isEqualTo(stored.candidateId());

        byte[] hash = new byte[32];
        new SecureRandom().nextBytes(hash);
        jdbc.update("""
                INSERT INTO job_applications (id,job_posting_id,requisition_id,full_name,email,normalized_email,phone,
                    received_at,posting_title,tracking_code_hash)
                VALUES (?,?,?,'Nguyễn Văn B','raw@example.test','raw@example.test','0912345678',?,'Lập trình viên Java',?)
                """, UUID.randomUUID(), otherPosting, applications.requisitionOf(otherPosting),
                Timestamp.from(ApplicationTestSupport.NOW), hash);
        assertThatThrownBy(() -> applications.stored(otherPosting, "Raw@Example.test"))
                .isInstanceOf(DuplicateApplicationException.class);
        assertThat(count("candidates")).isEqualTo(1);
        assertThat(count("candidate_applications")).isEqualTo(1);
    }

    // Two first applications of one email into two requisitions: the second waits for the first on the profile's
    // unique index only (it already holds its own application row), and sees the outcome. Committed: one profile with
    // two links. Rolled back: the second creates the profile itself. Neither waits for anything the other needs.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void twoFirstApplicationsOfOneEmailMeetOnlyAtTheProfile(boolean rollbackFirst) throws Exception {
        var stored = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var firstPid = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                var saved = applications.stored(posting, "same@example.test");
                firstPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                stored.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test gate timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                if (rollbackFirst) status.setRollbackOnly();
                return saved;
            }));
            try {
                assertThat(stored.await(10, TimeUnit.SECONDS)).isTrue();
                clock.set(LATER);
                var second = executor.submit(() -> applications.stored(otherPosting, " Same@Example.test"));
                int waiter = applications.awaitWaiterOf(firstPid.get());
                assertThat(jdbc.queryForObject("SELECT query FROM pg_stat_activity WHERE pid = ?", String.class, waiter))
                        .contains("INSERT INTO candidates");
                release.countDown();
                var firstStored = first.get(20, TimeUnit.SECONDS);
                var secondStored = second.get(20, TimeUnit.SECONDS);

                if (rollbackFirst) {
                    assertThat(count("job_applications")).isEqualTo(1);
                    assertThat(count("candidate_applications")).isEqualTo(1);
                    assertThat(jdbc.queryForObject("SELECT created_at FROM candidates WHERE id = ?", Timestamp.class,
                            secondStored.candidateId())).isEqualTo(Timestamp.from(LATER));
                } else {
                    assertThat(secondStored.candidateId()).isEqualTo(firstStored.candidateId());
                    assertThat(count("candidate_applications")).isEqualTo(2);
                    assertThat(jdbc.queryForObject("SELECT created_at FROM candidates WHERE id = ?", Timestamp.class,
                            firstStored.candidateId())).isEqualTo(Timestamp.from(ApplicationTestSupport.NOW));
                }
                assertThat(jdbc.queryForObject("SELECT normalized_email FROM candidates", String.class))
                        .isEqualTo("same@example.test");
            } finally {
                release.countDown();
            }
        }
    }

    // The profile's unique index is the last wait of store(), so the deadline is checked again after it (the rule of
    // task 326's catalog wait, one wait later): a first application that passes the check at 23:59:59 on the deadline
    // day and then waits for a concurrent first application of the same email until after midnight is refused like a
    // late one (409 JOB_POSTING_EXPIRED) and leaves no application, CV, link or profile of its own.
    @Test
    void aWaitAtTheProfileThatEndsAfterTheDeadlineRefusesTheApplication() throws Exception {
        jdbc.update("UPDATE job_postings SET application_deadline = ? WHERE id IN (?, ?)", LocalDate.of(2026, 10, 15),
                posting, otherPosting);
        clock.set(Instant.parse("2026-10-15T16:59:59Z"));                 // 23:59:59 in Vietnam on the deadline day
        var stored = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var firstPid = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                var saved = applications.stored(posting, "late@example.test");
                firstPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                stored.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test gate timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return saved;
            }));
            try {
                assertThat(stored.await(10, TimeUnit.SECONDS)).isTrue();
                var second = executor.submit(() -> applications.stored(otherPosting, " Late@Example.test"));
                int waiter = applications.awaitWaiterOf(firstPid.get());
                assertThat(jdbc.queryForObject("SELECT query FROM pg_stat_activity WHERE pid = ?", String.class, waiter))
                        .contains("INSERT INTO candidates");
                clock.set(Instant.parse("2026-10-15T17:00:00Z"));             // business midnight: the deadline passed
                release.countDown();
                var firstStored = first.get(20, TimeUnit.SECONDS);
                assertThatThrownBy(() -> second.get(20, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                        .cause().isInstanceOf(ApiException.class)
                        .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT)
                        .hasFieldOrPropertyWithValue("code", "JOB_POSTING_EXPIRED");

                assertThat(count("job_applications")).isEqualTo(1);
                assertThat(count("application_cvs")).isEqualTo(1);
                assertThat(count("candidate_applications")).isEqualTo(1);
                assertThat(count("candidates")).isEqualTo(1);
                assertThat(applications.candidateOf(firstStored.applicationId())).isEqualTo(firstStored.candidateId());
                assertThat(jdbc.queryForObject("SELECT count(*) FROM job_applications WHERE job_posting_id = ?",
                        Integer.class, otherPosting)).isZero();
            } finally {
                release.countDown();
            }
        }
    }

    // The link joins the caller's transaction: a rollback leaves no profile and no link.
    @Test
    void aRolledBackStoreLeavesNoProfile() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            var stored = applications.stored(posting, "rollback@example.test");
            assertThat(applications.candidateOf(stored.applicationId())).isEqualTo(stored.candidateId());
            assertThat(count("candidates")).isEqualTo(1);
            status.setRollbackOnly();
        });
        assertThat(count("candidates")).isZero();
        assertThat(count("candidate_applications")).isZero();
        assertThat(count("job_applications")).isZero();
    }

    // The CV is inserted after the link: when it fails, the new profile goes with the application.
    @Test
    void aFailedCvInsertTakesTheNewProfileWithIt() {
        jdbc.execute("CREATE FUNCTION fail_candidate_cv_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test CV failure'; END $$");
        jdbc.execute("CREATE TRIGGER fail_candidate_cv_test BEFORE INSERT ON application_cvs FOR EACH ROW EXECUTE FUNCTION fail_candidate_cv_test()");
        try {
            assertThatThrownBy(() -> applications.stored(posting, "cv@example.test")).isInstanceOf(RuntimeException.class);
            assertThat(count("candidates")).isZero();
            assertThat(count("candidate_applications")).isZero();
            assertThat(count("job_applications")).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER fail_candidate_cv_test ON application_cvs");
            jdbc.execute("DROP FUNCTION fail_candidate_cv_test()");
        }
        var stored = applications.stored(posting, "cv@example.test");
        assertThat(stored.candidateId()).isNotNull();
        assertThat(count("candidates")).isEqualTo(1);
    }

    // The production path of the public intake (task 329's receipt writer, its own REQUIRES_NEW transaction) links too.
    @Test
    void anApplicationThroughTheReceiptWriterIsLinked() {
        var receipt = receipts.accept(posting, new ApplicationSubmission(ApplicationTestSupport.FULL_NAME,
                "Receipt@Example.test", ApplicationTestSupport.PHONE, null, null), applications.pdf());
        UUID candidate = applications.candidateOf(receipt.applicationId());
        assertThat(jdbc.queryForMap("SELECT normalized_email, created_at FROM candidates WHERE id = ?", candidate))
                .containsEntry("normalized_email", "receipt@example.test")
                .containsEntry("created_at", Timestamp.from(receipt.receivedAt()));
        assertThat(count("candidate_applications")).isEqualTo(1);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
