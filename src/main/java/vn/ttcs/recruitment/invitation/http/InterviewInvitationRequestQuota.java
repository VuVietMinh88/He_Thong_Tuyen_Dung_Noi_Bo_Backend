package vn.ttcs.recruitment.invitation.http;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.common.RetryAfterApiException;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/** Independent fixed-window RSVP quota: 120 requests per 15 minutes, without storing the raw client IP. */
@Component
public class InterviewInvitationRequestQuota {
    static final int MAX_ATTEMPTS = 120;
    static final int WINDOW_SECONDS = 900;
    static final int CLEANUP_BATCH = 500;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final AtomicInteger cleanupCadence = new AtomicInteger();

    public InterviewInvitationRequestQuota(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        transaction = new TransactionTemplate(manager);
        // An invalid token or a failed RSVP must not roll back its admission to this public API.
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(5);
    }

    public void checkAndRecord(String resolvedIp) {
        long retry;
        try {
            String key = bucketKey(resolvedIp);
            // Cleanup commits before admission, so it holds no unrelated bucket locks while this request waits.
            if ((cleanupCadence.incrementAndGet() & 63) == 0) deleteExpired();
            retry = Objects.requireNonNull(transaction.execute(status -> record(key)));
        } catch (RuntimeException failure) {
            throw unavailable();
        }
        if (retry > 0) {
            throw new RetryAfterApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "INTERVIEW_INVITATION_RATE_LIMIT_EXCEEDED",
                    "Bạn đã truy cập lời mời quá số lượt cho phép. Vui lòng thử lại sau.", retry);
        }
    }

    private long record(String key) {
        timeouts();
        for (int attempt = 0; attempt < 3; attempt++) {
            int inserted = jdbc.update("""
                    INSERT INTO interview_invitation_request_buckets(bucket_key,window_started_at,attempts)
                    VALUES (?,clock_timestamp(),1) ON CONFLICT (bucket_key) DO NOTHING
                    """, key);
            if (inserted == 1) return 0;
            var rows = jdbc.query("""
                    SELECT window_started_at,attempts FROM interview_invitation_request_buckets
                    WHERE bucket_key=? FOR UPDATE
                    """, (row, number) -> new Window(row.getTimestamp(1).toInstant(), row.getInt(2)), key);
            // A concurrent cleanup may have removed this idle row after INSERT DO NOTHING.
            if (rows.isEmpty()) continue;
            Window window = rows.getFirst();
            Instant now = Objects.requireNonNull(jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class)).toInstant();
            Instant ends = window.startedAt().plusSeconds(WINDOW_SECONDS);
            if (!now.isBefore(ends)) {
                jdbc.update("""
                        UPDATE interview_invitation_request_buckets SET window_started_at=?,attempts=1 WHERE bucket_key=?
                        """, Timestamp.from(now), key);
                return 0;
            }
            if (window.attempts() >= MAX_ATTEMPTS) {
                Duration wait = Duration.between(now, ends);
                return Math.max(1, wait.getSeconds() + (wait.getNano() > 0 ? 1 : 0));
            }
            jdbc.update("UPDATE interview_invitation_request_buckets SET attempts=attempts+1 WHERE bucket_key=?", key);
            return 0;
        }
        throw new IllegalStateException("Invitation request quota is unavailable");
    }

    /** Remove at most 500 expired buckets; busy buckets are left for a later request. */
    public int deleteExpired() {
        return Objects.requireNonNull(transaction.execute(status -> {
            timeouts();
            return jdbc.update("""
                    WITH expired AS (
                        SELECT bucket_key FROM interview_invitation_request_buckets
                        WHERE window_started_at<=clock_timestamp()-INTERVAL '15 minutes'
                        ORDER BY window_started_at,bucket_key LIMIT 500 FOR UPDATE SKIP LOCKED
                    )
                    DELETE FROM interview_invitation_request_buckets b USING expired e
                    WHERE b.bucket_key=e.bucket_key
                    """);
        }));
    }

    // Numeric input only, supplied by ApplicationClientIpResolver. IPv6 /64 matches the other public API's quota.
    // This is pseudonymization, not encryption: do not treat an unsalted IP hash as anonymous personal data.
    static String bucketKey(String resolvedIp) {
        if (resolvedIp == null || resolvedIp.isEmpty() || resolvedIp.length() > 45
                || !resolvedIp.matches("[0-9a-fA-F:.]+")) throw new IllegalArgumentException("Not a numeric IP literal");
        if (resolvedIp.indexOf(':') < 0) {
            String[] parts = resolvedIp.split("\\.", -1);
            if (parts.length != 4) throw new IllegalArgumentException("Not a numeric IP literal");
            for (String part : parts) {
                if (!part.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(part) > 255) {
                    throw new IllegalArgumentException("Not a numeric IP literal");
                }
            }
        }
        try {
            InetAddress address = InetAddress.getByName(resolvedIp);
            byte[] bytes = address.getAddress();
            if (address instanceof Inet6Address) Arrays.fill(bytes, 8, 16, (byte) 0);
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            hash.update("interview-invitation-quota:v1:".getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(hash.digest(bytes));
        } catch (UnknownHostException | NoSuchAlgorithmException failure) {
            throw new IllegalArgumentException("Not a numeric IP literal");
        }
    }

    private void timeouts() {
        jdbc.execute("SET LOCAL lock_timeout='2s'");
        jdbc.execute("SET LOCAL statement_timeout='3s'");
    }

    public static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "INTERVIEW_INVITATION_UNAVAILABLE",
                "Chưa thể xử lý lời mời lúc này. Vui lòng thử lại sau.");
    }

    private record Window(Instant startedAt, int attempts) { }
}
