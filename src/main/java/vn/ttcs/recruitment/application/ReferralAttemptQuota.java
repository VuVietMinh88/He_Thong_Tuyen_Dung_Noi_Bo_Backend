package vn.ttcs.recruitment.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.common.RetryAfterApiException;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Task 351 (story S4-07): the daily limit of the referral API, on V37's own tables. Every referral that passed
 * validation and the CV check is one attempt, recorded and committed in its own transaction before the referral itself,
 * whatever the referral then answers: a 409 (the email already applied to that requisition) or a 404 rolls the referral
 * back but keeps its attempt, so the endpoint cannot be used to learn for free who applied where, nor to send
 * confirmation emails without limit. An attempt at t counts while t > now - 24 h; at app.referral.max-per-day live
 * attempts (1..100, default 20) the next one is refused with 429 and is not recorded. The rows are never deleted: they
 * are also the audit trail of who tried which posting and what came back (complete). No candidate data.
 * <p>
 * The algorithm is the intake's (PostgresApplicationAttemptQuota, V27) per referrer instead of per IP: the caller's
 * referral_quotas row is inserted if missing and locked FOR UPDATE, so concurrent attempts of one referrer are counted
 * one after the other; the clock (clock_timestamp()) is read after that lock. Only that row is locked, and the
 * transaction commits before the referral takes any lock, so a long referral of the same person (which holds the
 * account row) never blocks the next one's check, and the quota never joins the referral's lock order.
 */
@Component
public class ReferralAttemptQuota {
    private static final Logger LOG = LoggerFactory.getLogger(ReferralAttemptQuota.class);
    static final Duration WINDOW = Duration.ofHours(24);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final int maxPerDay;

    public ReferralAttemptQuota(JdbcTemplate jdbc, PlatformTransactionManager manager,
            @Value("${app.referral.max-per-day:20}") int maxPerDay) {
        if (maxPerDay < 1 || maxPerDay > 100) throw new IllegalArgumentException("Referral max per day must be 1..100");
        this.jdbc = jdbc; this.maxPerDay = maxPerDay;
        transaction = new TransactionTemplate(manager);
        // Commits on its own, before the referral: the referral's rollback never undoes a counted attempt.
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(5);
    }

    // The id of the recorded attempt, or 429 REFERRAL_RATE_LIMIT_EXCEEDED with Retry-After (nothing recorded), or 503
    // REFERRAL_SUBMISSION_UNAVAILABLE when the quota could not be read or written.
    long checkAndRecord(UUID referrerId, UUID postingId) {
        Admission admission;
        try { admission = Objects.requireNonNull(transaction.execute(status -> record(referrerId, postingId))); }
        catch (RuntimeException exception) { throw ApplicationReferralService.submissionUnavailable(); }
        if (admission.retryAfterSeconds() > 0) {
            throw new RetryAfterApiException(HttpStatus.TOO_MANY_REQUESTS, "REFERRAL_RATE_LIMIT_EXCEEDED",
                    "Bạn đã gửi giới thiệu quá số lượt cho phép trong 24 giờ. Vui lòng thử lại sau.",
                    admission.retryAfterSeconds());
        }
        return admission.attemptId();
    }

    // Best effort, in its own transaction: a failure is logged with static text and never changes the answer; the
    // attempt then keeps a NULL outcome. Only an attempt without an outcome is completed.
    void complete(long attemptId, ReferralAttemptOutcome outcome) {
        try {
            transaction.executeWithoutResult(status -> {
                jdbc.execute("SET LOCAL lock_timeout = '2s'");
                jdbc.execute("SET LOCAL statement_timeout = '3s'");
                jdbc.update("UPDATE referral_attempts SET outcome = ? WHERE id = ? AND outcome IS NULL",
                        outcome.name(), attemptId);
            });
        } catch (RuntimeException exception) {
            LOG.warn("Recording a referral attempt outcome failed; the attempt keeps no outcome");
        }
    }

    private Admission record(UUID referrerId, UUID postingId) {
        jdbc.execute("SET LOCAL lock_timeout = '2s'");
        jdbc.execute("SET LOCAL statement_timeout = '3s'");
        boolean locked = false;
        // The V27 pattern: insert the bucket if missing, then lock it (nothing deletes these rows today).
        for (int attempt = 0; attempt < 3 && !locked; attempt++) {
            jdbc.update("""
                    INSERT INTO referral_quotas(referrer_id,last_attempt_at) VALUES (?,clock_timestamp())
                    ON CONFLICT (referrer_id) DO NOTHING
                    """, referrerId);
            locked = !jdbc.query("SELECT referrer_id FROM referral_quotas WHERE referrer_id=? FOR UPDATE",
                    (row, number) -> true, referrerId).isEmpty();
        }
        if (!locked) throw new IllegalStateException("Referral quota row unavailable");
        // Read after the lock, not at transaction start: a waiting attempt must use its actual admission time.
        Instant now = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
        var live = jdbc.query("""
                SELECT attempted_at FROM referral_attempts WHERE referrer_id=? AND attempted_at>? ORDER BY attempted_at
                """, (row, number) -> row.getTimestamp(1).toInstant(), referrerId, Timestamp.from(now.minus(WINDOW)));
        if (live.size() >= maxPerDay) {
            // Once this entry leaves the window, fewer than maxPerDay remain (also after the limit was lowered).
            Duration wait = Duration.between(now, live.get(live.size() - maxPerDay).plus(WINDOW));
            return new Admission(0, Math.max(1, wait.getSeconds() + (wait.getNano() > 0 ? 1 : 0)));
        }
        Long id = jdbc.queryForObject("""
                INSERT INTO referral_attempts(referrer_id,job_posting_id,attempted_at) VALUES (?,?,?) RETURNING id
                """, Long.class, referrerId, postingId, Timestamp.from(now));
        jdbc.update("UPDATE referral_quotas SET last_attempt_at=? WHERE referrer_id=?", Timestamp.from(now), referrerId);
        return new Admission(Objects.requireNonNull(id), 0);
    }

    private record Admission(long attemptId, long retryAfterSeconds) { }
}
