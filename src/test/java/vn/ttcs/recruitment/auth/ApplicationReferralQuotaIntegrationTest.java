package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.application.ApplicationStorageService;
import vn.ttcs.recruitment.application.CvValidator;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 351 (story S4-07): the referral attempt limit (V37) over real HTTP and PostgreSQL, with app.referral.max-per-day
 * lowered to 3 (and room for eight referrals at once, so a parallel test meets the limit, never the busy gate). Every
 * attempt that passed validation and the CV check counts for 24 hours, whatever it answered, in its own committed
 * transaction; a refused (429) attempt and an invalid form count nothing. The window is the database clock
 * (clock_timestamp()); tests age attempts by SQL.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=false", "debug=false", "logging.level.root=WARN",
        "app.auth.jwt-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.application-confirmation.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.application-confirmation.page-url=https://portal.example.test/tra-cuu",
        "app.application-confirmation.poll-delay-ms=3600000", "app.application-intake.cleanup-delay-ms=3600000",
        "app.application-tracking.cleanup-delay-ms=3600000", "app.application-notice.poll-delay-ms=3600000",
        "app.referral.max-per-day=3", "app.referral.max-concurrent=8",
        "spring.mail.host=127.0.0.1", "spring.mail.port=1"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApplicationReferralQuotaIntegrationTest {
    private static final String LIMITED = "REFERRAL_RATE_LIMIT_EXCEEDED";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private Environment environment;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ApplicationStorageService storage;
    @Autowired private CvValidator validator;
    @Autowired private AuthIntegrationTest.MutableClock clock;
    @Autowired private TokenService tokens;

    private ApplicationTestSupport applications;
    private UUID posting;
    private UUID referrer;
    private String token;

    @BeforeEach
    void fixture() {
        clock.set(ApplicationTestSupport.NOW);
        int port = Integer.parseInt(environment.getRequiredProperty("local.server.port"));
        applications = new ApplicationTestSupport(jdbc, json, storage, validator, port);
        applications.reset();
        new JobPostingTestSupport(jdbc, json, transactions, port).reset();
        posting = applications.publishedPosting("Lập trình viên Java");
        referrer = applications.account("Trần Văn Nam", "nam@staff.test", Role.RECRUITER);
        token = applications.token(tokens, referrer);
    }

    // 201, 409 and 404 all count: the fourth valid referral is refused with Retry-After and stores nothing. The 409's
    // attempt survives the writer's rollback, because it was committed before.
    @Test
    void storedDuplicateAndUnavailableAttemptsAllCount() throws Exception {
        assertThat(applications.refer(token, posting, "a@example.test").statusCode()).isEqualTo(201);
        assertThat(applications.refer(token, posting, "a@example.test").statusCode()).isEqualTo(409);
        UUID unknown = UUID.randomUUID();
        assertThat(applications.refer(token, unknown, "a@example.test").statusCode()).isEqualTo(404);

        var limited = applications.refer(token, posting, "b@example.test");
        assertLimited(limited);
        long retry = Long.parseLong(limited.headers().firstValue("Retry-After").orElseThrow());
        assertThat(retry).isBetween(86_000L, 86_400L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_applications WHERE normalized_email = 'b@example.test'",
                Integer.class)).isZero();
        assertThat(jdbc.queryForList("SELECT job_posting_id, outcome FROM referral_attempts ORDER BY id"))
                .containsExactly(Map.of("job_posting_id", posting, "outcome", "STORED"),
                        Map.of("job_posting_id", posting, "outcome", "DUPLICATE"),
                        Map.of("job_posting_id", unknown, "outcome", "POSTING_UNAVAILABLE"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_applications", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_referrals", Integer.class)).isEqualTo(1);
    }

    // A bad email, a missing consent or an invalid CV is refused before the quota: none of them uses an attempt.
    @Test
    void invalidFormsUseNoAttempt() throws Exception {
        var badEmail = ApplicationTestSupport.referralFields(posting, "not-an-email");
        var noConsent = ApplicationTestSupport.referralFields(posting, "a@example.test");
        noConsent.remove("candidateConsent");
        for (int round = 0; round < 2; round++) {
            assertThat(applications.refer(token, badEmail, "CV.pdf", applications.pdf().content()).statusCode())
                    .isEqualTo(400);
            assertThat(applications.refer(token, noConsent, "CV.pdf", applications.pdf().content()).statusCode())
                    .isEqualTo(400);
            assertThat(applications.refer(token, ApplicationTestSupport.referralFields(posting, "a@example.test"),
                    "CV.pdf", "%PDF-1.7 broken".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).statusCode())
                    .isEqualTo(400);
        }
        assertThat(applications.referralAttempts()).isZero();
        for (String email : List.of("a@example.test", "b@example.test", "c@example.test")) {
            assertThat(applications.refer(token, posting, email).statusCode()).isEqualTo(201);
        }
        assertLimited(applications.refer(token, posting, "d@example.test"));
    }

    // Attempts older than 24 hours no longer count.
    @Test
    void attemptsOlderThanADayNoLongerCount() throws Exception {
        for (String email : List.of("a@example.test", "b@example.test", "c@example.test")) {
            assertThat(applications.refer(token, posting, email).statusCode()).isEqualTo(201);
        }
        assertLimited(applications.refer(token, posting, "d@example.test"));
        jdbc.update("UPDATE referral_attempts SET attempted_at = attempted_at - INTERVAL '24 hours 1 second'");
        assertThat(applications.refer(token, posting, "d@example.test").statusCode()).isEqualTo(201);
        assertThat(applications.referralAttempts()).isEqualTo(4);
    }

    // Five live attempts after the limit was lowered to 3: the next one waits until the third-oldest leaves the window
    // (fewer than 3 then remain), not the oldest.
    @Test
    void retryAfterWaitsForTheEntryThatBringsTheCountBelowTheLimit() throws Exception {
        for (int age : List.of(20, 19, 18, 17, 16)) {
            jdbc.update("INSERT INTO referral_attempts(referrer_id, job_posting_id, attempted_at, outcome)"
                    + " VALUES (?, ?, clock_timestamp() - make_interval(hours => ?), 'STORED')", referrer, posting, age);
        }
        var limited = applications.refer(token, posting, "a@example.test");
        assertLimited(limited);
        long retry = Long.parseLong(limited.headers().firstValue("Retry-After").orElseThrow());
        assertThat(retry).isBetween(6 * 3600L - 30, 6 * 3600L + 1);
        assertThat(applications.referralAttempts()).isEqualTo(5);
    }

    // Each referrer has their own bucket.
    @Test
    void oneReferrersAttemptsNeverCountForAnother() throws Exception {
        UUID second = applications.account("Lê Thị Mai", "mai@staff.test", Role.HIRING_MANAGER);
        UUID third = applications.account("Phạm Văn Long", "long@staff.test", Role.APPROVER);
        for (String email : List.of("a@example.test", "b@example.test", "c@example.test")) {
            assertThat(applications.refer(token, posting, email).statusCode()).isEqualTo(201);
        }
        assertLimited(applications.refer(token, posting, "d@example.test"));
        assertThat(applications.refer(applications.token(tokens, second), posting, "d@example.test").statusCode())
                .isEqualTo(201);
        assertThat(applications.refer(applications.token(tokens, third), posting, "e@example.test").statusCode())
                .isEqualTo(201);
        for (var expected : List.of(Map.entry(referrer, 3), Map.entry(second, 1), Map.entry(third, 1))) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM referral_attempts WHERE referrer_id = ?", Integer.class,
                    expected.getKey())).isEqualTo(expected.getValue());
        }
    }

    // Three referrals of one referrer at the same moment with two attempts left: the referrer's quota row serialises
    // them, so exactly two are admitted (and stored) and one is refused.
    @Test
    void parallelAttemptsOfOneReferrerAreCountedOneAfterTheOther() throws Exception {
        jdbc.update("INSERT INTO referral_attempts(referrer_id, job_posting_id, attempted_at, outcome)"
                + " VALUES (?, ?, clock_timestamp() - INTERVAL '1 hour', 'STORED')", referrer, posting);
        assertThat(applications.refer(token, applications.publishedPosting("Khởi động"), "warm@example.test")
                .statusCode()).isEqualTo(201);      // warm-up of the HTTP path; one attempt left after it
        jdbc.update("DELETE FROM referral_attempts WHERE job_posting_id <> ?", posting);
        var barrier = new CyclicBarrier(3);
        List<Integer> statuses = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(3)) {
            List<Future<HttpResponse<String>>> calls = new ArrayList<>();
            for (String email : List.of("a@example.test", "b@example.test", "c@example.test")) {
                calls.add(executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return applications.refer(token, posting, email);
                }));
            }
            for (var call : calls) statuses.add(call.get(30, TimeUnit.SECONDS).statusCode());
        }
        assertThat(statuses).containsExactlyInAnyOrder(201, 201, 429);
        assertThat(applications.referralAttempts()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_referrals ar JOIN job_applications a"
                + " ON a.id = ar.application_id WHERE a.job_posting_id = ?", Integer.class, posting)).isEqualTo(2);
    }

    // V37: the outcome CHECK takes exactly the five outcomes; both tables keep the referrer RESTRICT.
    @Test
    void theSchemaKeepsTheOutcomesAndTheReferrer() {
        for (String outcome : List.of("STORED", "POSTING_UNAVAILABLE", "DUPLICATE", "REFUSED", "FAILED")) {
            jdbc.update("INSERT INTO referral_attempts(referrer_id, job_posting_id, attempted_at, outcome)"
                    + " VALUES (?, ?, clock_timestamp(), ?)", referrer, posting, outcome);
        }
        jdbc.update("INSERT INTO referral_attempts(referrer_id, job_posting_id, attempted_at) VALUES (?, ?, clock_timestamp())",
                referrer, posting);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO referral_attempts(referrer_id, job_posting_id, attempted_at,"
                + " outcome) VALUES (?, ?, clock_timestamp(), 'LIMITED')", referrer, posting))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO referral_quotas(referrer_id, last_attempt_at) VALUES (?, clock_timestamp())", referrer);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM user_accounts WHERE id = ?", referrer))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("DELETE FROM referral_attempts");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM user_accounts WHERE id = ?", referrer))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname = 'referral_attempts_referrer_idx'",
                Integer.class)).isEqualTo(1);
    }

    private void assertLimited(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(429);
        var body = json.readTree(response.body());
        assertThat(body.get("code").asString()).isEqualTo(LIMITED);
        assertThat(body.get("message").asString())
                .isEqualTo("Bạn đã gửi giới thiệu quá số lượt cho phép trong 24 giờ. Vui lòng thử lại sau.");
        assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(value -> assertThat(value).contains("no-store"));
        long retry = Long.parseLong(response.headers().firstValue("Retry-After").orElseThrow());
        assertThat(retry).isBetween(1L, 86_400L);
    }
}
