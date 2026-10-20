package vn.ttcs.recruitment.messaging;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Task 538: the candidate_mail_outbox queue (V66_3). enqueue joins the deciding transaction; every worker step is its
 * own short transaction fenced by the lease token, and SMTP never runs inside one, as in the other outboxes. Up to six
 * automatic attempts with growing waits; a lease that expires on the sixth attempt ends as DELIVERY_UNCERTAIN.
 */
@Repository
public class CandidateMailOutboxRepository {
    private static final long[] RETRY_SECONDS = {60, 300, 900, 3600, 21600};
    private static final Set<String> PERMANENT_FAILURES = Set.of("RECIPIENT_REFUSED", "PAYLOAD_INVALID");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public CandidateMailOutboxRepository(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transactions.setTimeout(5);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(UUID id, UUID messageId) {
        jdbc.update("INSERT INTO candidate_mail_outbox(id, message_id) VALUES (?, ?)", id, messageId);
    }

    Optional<Delivery> claim() {
        return transactions.execute(status -> {
            timeouts();
            jdbc.update("""
                    WITH expired AS (
                        SELECT id FROM candidate_mail_outbox
                        WHERE status='PROCESSING' AND lease_until<=clock_timestamp() AND attempts=6
                        ORDER BY lease_until LIMIT 100 FOR UPDATE SKIP LOCKED
                    )
                    UPDATE candidate_mail_outbox o SET status='FAILED',lease_token=NULL,lease_until=NULL,
                        last_failure='DELIVERY_UNCERTAIN',updated_at=clock_timestamp()
                    FROM expired e WHERE o.id=e.id
                    """);
            return jdbc.query("""
                    WITH candidate AS (
                        SELECT id FROM candidate_mail_outbox WHERE attempts<6 AND (
                            (status IN ('PENDING','RETRY_WAIT') AND next_attempt_at<=clock_timestamp())
                            OR (status='PROCESSING' AND lease_until<=clock_timestamp()))
                        ORDER BY next_attempt_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
                    )
                    UPDATE candidate_mail_outbox o SET status='PROCESSING',attempts=o.attempts+1,
                        lease_token=?,lease_until=clock_timestamp()+INTERVAL '2 minutes',updated_at=clock_timestamp()
                    FROM candidate c WHERE o.id=c.id
                    RETURNING o.id,o.message_id,o.lease_token,o.attempts
                    """, (row, number) -> new Delivery(row.getObject("id", UUID.class),
                    row.getObject("message_id", UUID.class), row.getObject("lease_token", UUID.class),
                    row.getInt("attempts")), UUID.randomUUID()).stream().findFirst();
        });
    }

    /** What the email needs now, read from the message, its application and its interview. Empty if it is gone. */
    Optional<Content> content(Delivery delivery) {
        return transactions.execute(status -> {
            timeouts();
            return jdbc.query("""
                    SELECT m.template_type, m.recipient_email, a.email, a.full_name, a.posting_title, a.stage,
                        m.interview_id, i.starts_at
                    FROM email_messages m JOIN job_applications a ON a.id = m.application_id
                    LEFT JOIN interview_sessions i ON i.id = m.interview_id AND i.application_id = m.application_id
                    WHERE m.id = ? AND m.delivery = 'CANDIDATE_MAIL' AND m.delivery_id = ?
                    """, (row, number) -> new Content(EmailTemplateType.valueOf(row.getString("template_type")),
                    row.getString("recipient_email"), row.getString("email"), row.getString("full_name"),
                    row.getString("posting_title"), row.getString("stage"), row.getObject("interview_id", UUID.class),
                    row.getTimestamp("starts_at") == null ? null : row.getTimestamp("starts_at").toInstant()),
                    delivery.messageId(), delivery.id()).stream().findFirst();
        });
    }

    boolean renew(Delivery delivery) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            timeouts();
            return jdbc.update("""
                    UPDATE candidate_mail_outbox SET lease_until=clock_timestamp()+INTERVAL '2 minutes',updated_at=clock_timestamp()
                    WHERE id=? AND status='PROCESSING' AND lease_token=? AND lease_until>clock_timestamp()
                    """, delivery.id(), delivery.leaseToken()) == 1;
        }));
    }

    boolean sent(Delivery delivery) {
        return finish(delivery, "SENT", null);
    }

    boolean abandon(Delivery delivery, String reason) {
        if (!Set.of("OBSOLETE", "RECIPIENT_UNAVAILABLE").contains(reason)) throw new IllegalArgumentException("Invalid failure code");
        return finish(delivery, "CANCELLED", reason);
    }

    boolean failed(Delivery delivery, boolean permanent, String failure) {
        if (permanent && !PERMANENT_FAILURES.contains(failure)) throw new IllegalArgumentException("Invalid failure code");
        if (permanent || delivery.attempts() >= 6) {
            return finish(delivery, "FAILED", permanent ? failure : "RETRIES_EXHAUSTED");
        }
        long delay = RETRY_SECONDS[delivery.attempts() - 1];
        return Boolean.TRUE.equals(transactions.execute(status -> {
            timeouts();
            return jdbc.update("""
                    UPDATE candidate_mail_outbox SET status='RETRY_WAIT',last_failure='SMTP_TEMPORARY',
                        next_attempt_at=clock_timestamp()+(? * INTERVAL '1 second'),updated_at=clock_timestamp(),
                        lease_token=NULL,lease_until=NULL
                    WHERE id=? AND status='PROCESSING' AND lease_token=?
                    """, delay, delivery.id(), delivery.leaseToken()) == 1;
        }));
    }

    private boolean finish(Delivery delivery, String result, String failure) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            timeouts();
            return jdbc.update("""
                    UPDATE candidate_mail_outbox SET status=?,last_failure=?,updated_at=clock_timestamp(),
                        sent_at=CASE WHEN ?='SENT' THEN clock_timestamp() ELSE NULL END,lease_token=NULL,lease_until=NULL
                    WHERE id=? AND status='PROCESSING' AND lease_token=?
                    """, result, failure, result, delivery.id(), delivery.leaseToken()) == 1;
        }));
    }

    private void timeouts() {
        jdbc.execute("SET LOCAL lock_timeout='2s'");
        jdbc.execute("SET LOCAL statement_timeout='3s'");
    }

    record Delivery(UUID id, UUID messageId, UUID leaseToken, int attempts) {
    }

    record Content(EmailTemplateType type, String recipient, String currentEmail, String candidateName,
                   String positionTitle, String stage, UUID interviewId, Instant interviewStartsAt) {
        @Override public String toString() { return "CandidateMailContent[" + type + ", personal data redacted]"; }
    }
}
