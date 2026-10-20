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
import vn.ttcs.recruitment.application.ApplicationStorageService;
import vn.ttcs.recruitment.application.CvValidator;
import vn.ttcs.recruitment.application.PostgresApplicationTrackingQuota;

import javax.sql.DataSource;
import java.net.http.HttpResponse;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 339: the durable quota of the public tracking API (V31), over real HTTP. Three attempts per bucket in a sliding
 * hour here (30 in production). No proxy is trusted, so every request comes from 127.0.0.1, one bucket. The cleanup
 * job never runs by itself (delay of an hour); the test calls it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=false", "debug=false", "logging.level.root=WARN",
        "app.auth.jwt-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.application-intake.enabled=true", "app.application-intake.trusted-proxies=",
        "app.application-intake.cleanup-delay-ms=3600000",
        "app.application-confirmation.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.application-confirmation.poll-delay-ms=3600000",
        "app.application-tracking.max-attempts=3", "app.application-tracking.cleanup-delay-ms=3600000",
        "spring.mail.host=127.0.0.1", "spring.mail.port=1"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApplicationTrackingQuotaIntegrationTest {
    private static final String EMAIL = "candidate@example.test";
    private static final String LOOPBACK = "127.0.0.1";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private Environment environment;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ApplicationStorageService storage;
    @Autowired private CvValidator validator;
    @Autowired private PostgresApplicationTrackingQuota quota;
    @Autowired private AuthIntegrationTest.MutableClock clock;

    private ApplicationTestSupport applications;
    private UUID posting;

    @BeforeEach
    void fixture() {
        clock.set(ApplicationTestSupport.NOW);
        int port = Integer.parseInt(environment.getRequiredProperty("local.server.port"));
        applications = new ApplicationTestSupport(jdbc, json, storage, validator, port);
        applications.reset();
        new JobPostingTestSupport(jdbc, json, transactions, port).reset();
        posting = applications.publishedPosting("Lập trình viên");
    }

    // Found (200) and not found (404) both count; a malformed request (400) never does; the refused 4th is not
    // recorded and does not move the window.
    @Test
    void theFourthWellFormedAttemptIsRefusedAndNotRecorded() throws Exception {
        var stored = applications.stored(posting, EMAIL);
        assertThat(applications.lookup(applications.unknownCode(), EMAIL).statusCode()).isEqualTo(404);
        assertThat(applications.lookup("too-short", EMAIL).statusCode()).isEqualTo(400);
        // 43 valid characters but not the canonical spelling: refused by the service, still before the quota.
        assertThat(applications.lookup(nonCanonical(stored.trackingCode()), EMAIL).statusCode()).isEqualTo(400);
        assertThat(applications.lookup(stored.trackingCode(), EMAIL).statusCode()).isEqualTo(200);
        assertThat(applications.lookup(stored.trackingCode(), "other@example.test").statusCode()).isEqualTo(404);
        assertThat(attempts(LOOPBACK)).isEqualTo(3);
        var before = jdbc.queryForList("SELECT * FROM application_tracking_ip_attempts ORDER BY id");
        var last = jdbc.queryForObject("SELECT last_attempt_at FROM application_tracking_ip_quotas", java.sql.Timestamp.class);

        var limited = applications.lookup(stored.trackingCode(), EMAIL, Map.of("Origin", "http://localhost:5173"));
        assertThat(limited.statusCode()).as(limited.body()).isEqualTo(429);
        assertThat(limited.headers().firstValue("Retry-After").map(Long::parseLong))
                .hasValueSatisfying(value -> assertThat(value).isBetween(3500L, 3600L));
        assertThat(limited.headers().firstValue("Cache-Control")).hasValueSatisfying(value -> assertThat(value).contains("no-store"));
        assertThat(limited.headers().firstValue("Access-Control-Expose-Headers"))
                .hasValueSatisfying(value -> assertThat(value).contains("Retry-After"));
        var body = json.readTree(limited.body());
        assertThat(body.propertyNames()).containsExactly("code", "message", "fieldErrors");
        assertThat(body.get("code").asString()).isEqualTo("APPLICATION_TRACKING_RATE_LIMIT_EXCEEDED");
        assertThat(body.get("message").asString())
                .isEqualTo("Bạn đã tra cứu hoặc rút hồ sơ quá số lượt cho phép. Vui lòng thử lại sau.");
        assertThat(limited.body()).doesNotContain(LOOPBACK, stored.trackingCode(), EMAIL);
        // Validation still comes first while the bucket is full.
        assertThat(applications.lookup("too-short", EMAIL).statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForList("SELECT * FROM application_tracking_ip_attempts ORDER BY id")).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT last_attempt_at FROM application_tracking_ip_quotas", java.sql.Timestamp.class))
                .isEqualTo(last);
    }

    @Test
    void theWindowSlidesRatherThanResettingByTheHour() throws Exception {
        for (int i = 0; i < 3; i++) assertThat(lookUp()).isEqualTo(404);
        assertThat(lookUp()).isEqualTo(429);
        jdbc.update("""
                UPDATE application_tracking_ip_attempts SET attempted_at = clock_timestamp() - INTERVAL '1 hour' - INTERVAL '1 second'
                WHERE id = (SELECT min(id) FROM application_tracking_ip_attempts)
                """);
        assertThat(lookUp()).isEqualTo(404);
        assertThat(attempts(LOOPBACK)).isEqualTo(3);
        assertThat(lookUp()).isEqualTo(429);
    }

    // Five live attempts against a limit of three (the limit was lowered): the wait ends when the third-oldest leaves
    // the window, since two remain then; waiting for the oldest only would answer too early.
    @Test
    void aLoweredLimitWaitsForTheEntryThatBringsTheCountBelowIt() throws Exception {
        jdbc.update("INSERT INTO application_tracking_ip_quotas(client_ip,last_attempt_at) VALUES (CAST(? AS inet), clock_timestamp())", LOOPBACK);
        for (int age : new int[] {3000, 2900, 2800, 2700, 2600}) {
            jdbc.update("""
                    INSERT INTO application_tracking_ip_attempts(client_ip,attempted_at)
                    VALUES (CAST(? AS inet), clock_timestamp() - make_interval(secs => ?))
                    """, LOOPBACK, age);
        }
        var limited = applications.lookup(applications.unknownCode(), EMAIL);
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.headers().firstValue("Retry-After").map(Long::parseLong))
                .hasValueSatisfying(value -> assertThat(value).isBetween(795L, 801L));
        assertThat(attempts(LOOPBACK)).isEqualTo(5);
    }

    // The tracking API and the intake have separate buckets: lookups never spend a submission, and the reverse.
    @Test
    void lookupsAndSubmissionsUseSeparateBuckets() throws Exception {
        for (int i = 0; i < 3; i++) assertThat(lookUp()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_ip_attempts", Integer.class)).isZero();
        // The intake counts its attempt before it looks at the body (task 328); a JSON body is then refused.
        var submission = applications.post("/api/v1/public/job-postings/" + posting + "/applications", "{}",
                "application/json", Map.of());
        assertThat(submission.statusCode()).as(submission.body()).isEqualTo(415);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_ip_attempts", Integer.class)).isEqualTo(1);
        assertThat(attempts(LOOPBACK)).isEqualTo(3);
        assertThat(lookUp()).isEqualTo(429);
    }

    // Two lookups from one address at the same moment, one attempt left: exactly one is admitted.
    @Test
    void simultaneousAttemptsFromOneAddressAreCountedExactly() throws Exception {
        for (int i = 0; i < 2; i++) assertThat(lookUp()).isEqualTo(404);
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return lookUp();
                }));
            }
            List<Integer> statuses = new ArrayList<>();
            for (var result : results) statuses.add(result.get(20, TimeUnit.SECONDS));
            assertThat(statuses).containsExactlyInAnyOrder(404, 429);
        }
        assertThat(attempts(LOOPBACK)).isEqualTo(3);
    }

    // One run removes every idle bucket in batches of 500 (here three batches), keeps live ones, and V31 keeps
    // buckets host addresses.
    @Test
    void cleanupRemovesIdleBucketsInBatchesAndKeepsLiveOnes() throws Exception {
        assertThat(lookUp()).isEqualTo(404);
        jdbc.execute("""
                INSERT INTO application_tracking_ip_quotas(client_ip,last_attempt_at)
                SELECT '198.18.0.0'::inet + n, clock_timestamp() - INTERVAL '61 minutes' FROM generate_series(1,1200) n
                """);
        jdbc.execute("""
                INSERT INTO application_tracking_ip_attempts(client_ip,attempted_at)
                SELECT '198.18.0.0'::inet + n, clock_timestamp() - INTERVAL '61 minutes' FROM generate_series(1,1200) n
                """);
        assertThat(quota.deleteExpired()).isEqualTo(1200);
        assertThat(jdbc.queryForList("SELECT host(client_ip) FROM application_tracking_ip_quotas", String.class))
                .containsExactly(LOOPBACK);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_tracking_ip_attempts", Integer.class)).isEqualTo(1);
        assertThat(quota.deleteExpired()).isZero();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO application_tracking_ip_quotas(client_ip,last_attempt_at) VALUES ('10.0.0.0/8', clock_timestamp())"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("application_tracking_ip_quotas_host_address");
    }

    // A run stops after twenty full batches (10 000 buckets) and leaves the rest to the next one.
    @Test
    void oneCleanupRunIsBoundedToTwentyBatches() {
        jdbc.execute("""
                INSERT INTO application_tracking_ip_quotas(client_ip,last_attempt_at)
                SELECT '198.18.0.0'::inet + n, clock_timestamp() - INTERVAL '61 minutes' FROM generate_series(1,10001) n
                """);
        assertThat(quota.deleteExpired()).isEqualTo(10_000);
        assertThat(quota.deleteExpired()).isEqualTo(1);
    }

    @Test
    void aHeldQuotaTableFailsClosedWithoutRecordingAnything() throws Exception {
        try (var blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.createStatement()) {
                statement.execute("LOCK TABLE application_tracking_ip_quotas IN ACCESS EXCLUSIVE MODE");
            }
            long started = System.nanoTime();
            HttpResponse<String> response;
            try {
                response = applications.lookup(applications.unknownCode(), EMAIL);
            } finally {
                blocker.rollback();
            }
            assertThat(response.statusCode()).as(response.body()).isEqualTo(503);
            assertThat(json.readTree(response.body()).get("code").asString()).isEqualTo("APPLICATION_TRACKING_UNAVAILABLE");
            assertThat(response.body()).doesNotContain("application_tracking", "Exception", LOOPBACK, "lock");
            assertThat(Duration.ofNanos(System.nanoTime() - started).toMillis()).isBetween(1500L, 8000L);
        }
        assertThat(attempts(LOOPBACK)).isZero();
        assertThat(lookUp()).isEqualTo(404);
        assertThat(attempts(LOOPBACK)).isEqualTo(1);
    }

    @Test
    void maxAttemptsOutsideOneToAThousandStopsStartup() {
        for (int invalid : new int[] {0, 1001}) {
            assertThatThrownBy(() -> new PostgresApplicationTrackingQuota(jdbc, transactions, invalid))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Application tracking max attempts must be 1..1000");
        }
        new PostgresApplicationTrackingQuota(jdbc, transactions, 1);
        new PostgresApplicationTrackingQuota(jdbc, transactions, 1000);
    }

    // The same 32 bytes spelled with one of the unused low bits of the last character set.
    private static String nonCanonical(String code) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        return code.substring(0, 42) + alphabet.charAt(alphabet.indexOf(code.charAt(42)) ^ 1);
    }

    private int lookUp() throws Exception {
        return applications.lookup(applications.unknownCode(), EMAIL).statusCode();
    }

    private int attempts(String bucket) {
        return jdbc.queryForObject("SELECT count(*) FROM application_tracking_ip_attempts WHERE client_ip = CAST(? AS inet)",
                Integer.class, bucket);
    }
}
