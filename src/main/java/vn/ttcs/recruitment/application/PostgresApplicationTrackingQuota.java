package vn.ttcs.recruitment.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.common.RetryAfterApiException;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Task 339 (story S4-05): durable sliding-window quota of the public tracking API, on V31's own tables, so a lookup
 * never spends one of the intake's five submissions per IP and hour. The algorithm is the intake's
 * (PostgresApplicationAttemptQuota, V27): every admitted attempt is one row, an attempt at t stops counting when
 * t <= now - 3600 s, and a refused attempt is not recorded. Differences: the bucket is TrackingBuckets' key (IPv6 per
 * /64), the limit is app.application-tracking.max-attempts (1..1000, default 30), and Retry-After waits for the entry
 * whose expiry brings the count below the limit, which stays right when the limit was lowered while more attempts
 * were live.
 */
@Component
public class PostgresApplicationTrackingQuota {
    static final int BATCH = 500;
    static final int MAX_BATCHES = 20;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final int maxAttempts;

    public PostgresApplicationTrackingQuota(JdbcTemplate jdbc, PlatformTransactionManager manager,
            @Value("${app.application-tracking.max-attempts:30}") int maxAttempts) {
        if (maxAttempts < 1 || maxAttempts > 1000) {
            throw new IllegalArgumentException("Application tracking max attempts must be 1..1000");
        }
        this.jdbc = jdbc; this.maxAttempts = maxAttempts;
        transaction = new TransactionTemplate(manager);
        // Commits on its own, before the lookup: the caller's work never undoes a counted attempt.
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(5);
    }

    // bucket is TrackingBuckets.of(...) of the resolved client address.
    void checkAndRecord(String bucket) {
        long retry;
        try { retry = Objects.requireNonNull(transaction.execute(status -> record(bucket))); }
        catch (RuntimeException exception) { throw unavailable(); }
        if (retry > 0) {
            throw new RetryAfterApiException(HttpStatus.TOO_MANY_REQUESTS, "APPLICATION_TRACKING_RATE_LIMIT_EXCEEDED",
                    "Bạn đã tra cứu hoặc rút hồ sơ quá số lượt cho phép. Vui lòng thử lại sau.", retry);
        }
    }

    private long record(String bucket) {
        jdbc.execute("SET LOCAL lock_timeout = '2s'");
        jdbc.execute("SET LOCAL statement_timeout = '3s'");
        boolean locked = false;
        // Cleanup may delete an idle row between INSERT DO NOTHING and SELECT FOR UPDATE.
        for (int attempt = 0; attempt < 3 && !locked; attempt++) {
            jdbc.update("""
                    INSERT INTO application_tracking_ip_quotas(client_ip,last_attempt_at)
                    VALUES (CAST(? AS inet),clock_timestamp())
                    ON CONFLICT (client_ip) DO NOTHING
                    """, bucket);
            locked = !jdbc.query("""
                    SELECT client_ip FROM application_tracking_ip_quotas WHERE client_ip=CAST(? AS inet) FOR UPDATE
                    """, (row, number) -> true, bucket).isEmpty();
        }
        if (!locked) throw new IllegalStateException("Quota row unavailable");
        // Read after the lock, not at transaction start: a waiting request must use its actual admission time.
        Instant now = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
        Instant cutoff = now.minusSeconds(3600);
        jdbc.update("DELETE FROM application_tracking_ip_attempts WHERE client_ip=CAST(? AS inet) AND attempted_at<=?",
                bucket, Timestamp.from(cutoff));
        var live = jdbc.query("""
                SELECT attempted_at FROM application_tracking_ip_attempts WHERE client_ip=CAST(? AS inet)
                ORDER BY attempted_at
                """, (row, number) -> row.getTimestamp(1).toInstant(), bucket);
        if (live.size() >= maxAttempts) {
            // Once this entry leaves the window, fewer than maxAttempts remain (also after the limit was lowered).
            Duration wait = Duration.between(now, live.get(live.size() - maxAttempts).plusSeconds(3600));
            return Math.max(1, wait.getSeconds() + (wait.getNano() > 0 ? 1 : 0));
        }
        jdbc.update("INSERT INTO application_tracking_ip_attempts(client_ip,attempted_at) VALUES (CAST(? AS inet),?)",
                bucket, Timestamp.from(now));
        jdbc.update("UPDATE application_tracking_ip_quotas SET last_attempt_at=? WHERE client_ip=CAST(? AS inet)",
                Timestamp.from(now), bucket);
        return 0;
    }

    // Deletes buckets idle for an hour (their attempts go by CASCADE), BATCH per short transaction, skipping buckets a
    // request holds, and repeats while a batch was full: at most MAX_BATCHES batches per call. Returns the count.
    public int deleteExpired() {
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            int deleted = Objects.requireNonNull(transaction.execute(status -> {
                jdbc.execute("SET LOCAL lock_timeout = '2s'");
                jdbc.execute("SET LOCAL statement_timeout = '3s'");
                return jdbc.update("""
                        WITH expired AS (
                            SELECT client_ip FROM application_tracking_ip_quotas
                            WHERE last_attempt_at<=clock_timestamp()-INTERVAL '1 hour'
                            ORDER BY last_attempt_at LIMIT 500 FOR UPDATE SKIP LOCKED
                        )
                        DELETE FROM application_tracking_ip_quotas q USING expired e WHERE q.client_ip=e.client_ip
                        """);
            }));
            total += deleted;
            if (deleted < BATCH) break;
        }
        return total;
    }

    static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "APPLICATION_TRACKING_UNAVAILABLE",
                "Chưa thể xử lý yêu cầu lúc này. Vui lòng thử lại sau.");
    }
}
