package vn.ttcs.recruitment.offerdelivery.http;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.auth.AuthIntegrationTest;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.common.RetryAfterApiException;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Disposable PostgreSQL only; there is no SMTP transport or offer worker enabled in this context. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "test.database.url=", "app.bootstrap.enabled=false", "debug=false", "logging.level.root=WARN",
        "app.auth.jwt-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.offers.enabled=false", "app.application-confirmation.poll-delay-ms=3600000",
        "app.application-notice.poll-delay-ms=3600000", "app.application-tracking.cleanup-delay-ms=3600000"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OfferRequestQuotaIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager manager;
    private OfferRequestQuota quota;

    @BeforeEach void reset() {
        jdbc.update("DELETE FROM offer_request_buckets");
        quota = new OfferRequestQuota(jdbc, manager);
    }

    @Test void quotaPersistsAcrossServiceInstancesAndRefusalDoesNotExtendTheWindow() {
        String address = "192.0.2.8";
        for (int i = 0; i < 120; i++) quota.checkAndRecord(address);
        var before = jdbc.queryForMap("SELECT attempts,window_started_at,bucket_key FROM offer_request_buckets");
        var restarted = new OfferRequestQuota(jdbc, manager);
        assertThatThrownBy(() -> restarted.checkAndRecord(address)).isInstanceOfSatisfying(RetryAfterApiException.class,
                failure -> {
                    assertThat(failure.getStatus().value()).isEqualTo(429);
                    assertThat(failure.getRetryAfterSeconds()).isBetween(1L, 900L);
                });
        var after = jdbc.queryForMap("SELECT attempts,window_started_at,bucket_key FROM offer_request_buckets");
        assertThat(after).isEqualTo(before);
        assertThat(after.get("bucket_key").toString()).matches("[0-9a-f]{64}");
        quota.checkAndRecord("192.0.2.9");
        assertThat(count()).isEqualTo(2);
    }

    @Test void expiredWindowStartsAtOneAndIpv6AddressesShareOnlyTheirSubnet() {
        String first = "2001:db8:1:1::a";
        quota.checkAndRecord(first);
        quota.checkAndRecord("2001:db8:1:1::b");
        assertThat(attempts(first)).isEqualTo(2);
        quota.checkAndRecord("2001:db8:1:2::a");
        assertThat(count()).isEqualTo(2);
        jdbc.update("""
                UPDATE offer_request_buckets
                SET attempts=120,window_started_at=clock_timestamp()-INTERVAL '15 minutes'
                WHERE bucket_key=?
                """, OfferRequestQuota.bucketKey(first));
        quota.checkAndRecord(first);
        assertThat(attempts(first)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT window_started_at>clock_timestamp()-INTERVAL '10 seconds'
                FROM offer_request_buckets WHERE bucket_key=?
                """, Boolean.class, OfferRequestQuota.bucketKey(first))).isTrue();
    }

    @Test void concurrentRequestsCannotSpendTheLastSlotMoreThanOnce() throws Exception {
        String address = "198.51.100.9";
        quota.checkAndRecord(address);
        jdbc.update("UPDATE offer_request_buckets SET attempts=119");
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(6)) {
            var results = new ArrayList<Future<Integer>>();
            for (int i = 0; i < 6; i++) {
                results.add(workers.submit(() -> {
                    if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test gate timed out");
                    try { quota.checkAndRecord(address); return 200; }
                    catch (ApiException failure) { return failure.getStatus().value(); }
                }));
            }
            start.countDown();
            var statuses = new ArrayList<Integer>();
            for (var result : results) statuses.add(result.get(10, TimeUnit.SECONDS));
            assertThat(statuses).containsExactlyInAnyOrder(200, 429, 429, 429, 429, 429);
            assertThat(attempts(address)).isEqualTo(120);
        }
    }

    @Test void failedBusinessTransactionCannotUndoAnAttempt() {
        var caller = new TransactionTemplate(manager);
        caller.executeWithoutResult(status -> {
            quota.checkAndRecord("203.0.113.7");
            status.setRollbackOnly();
        });
        assertThat(attempts("203.0.113.7")).isEqualTo(1);
    }

    @Test void cleanupIsBoundedSkipsLockedBucketsAndKeepsCurrentWindows() throws Exception {
        jdbc.update("""
                INSERT INTO offer_request_buckets(bucket_key,window_started_at,attempts)
                SELECT lpad(n::text,64,'0'),clock_timestamp()-INTERVAL '16 minutes',1 FROM generate_series(1,512) n
                """);
        quota.checkAndRecord("203.0.113.20");
        try (var locked = dataSource.getConnection()) {
            locked.setAutoCommit(false);
            try (var statement = locked.prepareStatement("""
                    SELECT 1 FROM offer_request_buckets WHERE bucket_key=? FOR UPDATE
                    """)) {
                statement.setString(1, "0".repeat(63) + "1");
                try (var row = statement.executeQuery()) { assertThat(row.next()).isTrue(); }
            }
            assertThat(quota.deleteExpired()).isEqualTo(500);
            assertThat(count()).isEqualTo(13);
            assertThat(quota.deleteExpired()).isEqualTo(11);
            assertThat(count()).isEqualTo(2);
            locked.rollback();
        }
        assertThat(quota.deleteExpired()).isEqualTo(1);
        assertThat(count()).isEqualTo(1);
        assertThat(attempts("203.0.113.20")).isEqualTo(1);
    }

    @Test void quotaStorageFailureIsAClosedGenericServiceUnavailable() {
        jdbc.execute("""
                CREATE FUNCTION fail_invitation_quota_test() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'private quota test detail'; END $$
                """);
        jdbc.execute("""
                CREATE TRIGGER fail_invitation_quota_test BEFORE INSERT ON offer_request_buckets
                FOR EACH ROW EXECUTE FUNCTION fail_invitation_quota_test()
                """);
        try {
            assertThatThrownBy(() -> quota.checkAndRecord("192.0.2.8")).isInstanceOfSatisfying(ApiException.class,
                    failure -> {
                        assertThat(failure.getStatus().value()).isEqualTo(503);
                        assertThat(failure.getCode()).isEqualTo("OFFER_UNAVAILABLE");
                        assertThat(failure.getMessage()).doesNotContain("private quota test detail", "192.0.2.8");
                    });
            assertThat(count()).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER fail_invitation_quota_test ON offer_request_buckets");
            jdbc.execute("DROP FUNCTION fail_invitation_quota_test()");
        }
        quota.checkAndRecord("192.0.2.8");
        assertThat(count()).isEqualTo(1);
    }

    private int attempts(String address) {
        return jdbc.queryForObject("SELECT attempts FROM offer_request_buckets WHERE bucket_key=?",
                Integer.class, OfferRequestQuota.bucketKey(address));
    }

    private int count() { return jdbc.queryForObject("SELECT count(*) FROM offer_request_buckets", Integer.class); }
}
