package vn.ttcs.recruitment.notification;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Task 551: notification_email_outbox (V66_9). enqueue joins NotificationWorker's transaction and stores ids only; the
 * worker steps are short transactions fenced by the lease token, with SMTP outside them, like the candidate outboxes.
 */
@Repository
public class NotificationEmailOutbox {
    private static final long[] RETRY_SECONDS = {60, 300, 900, 3600, 21600};
    private static final Set<String> CANCEL_REASONS =
            Set.of("RECIPIENT_UNAVAILABLE", "OBSOLETE", "PREFERENCE_OFF", "EXPIRED");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public NotificationEmailOutbox(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transactions.setTimeout(5);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean enqueue(UUID eventId, UUID recipient, NotificationType type, String dedupeKey) {
        return jdbc.update("""
                INSERT INTO notification_email_outbox(id, event_id, recipient_id, notification_type, dedupe_key)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT (recipient_id, dedupe_key) DO NOTHING
                """, UUID.randomUUID(), eventId, recipient, type.name(), dedupeKey) == 1;
    }

    /** Claims one due row; expired tells whether it was queued longer ago than maxAge (database time). */
    Optional<Delivery> claim(Duration maxAge) {
        return transactions.execute(status -> {
            timeouts();
            jdbc.update("""
                    WITH expired AS (
                        SELECT id FROM notification_email_outbox
                        WHERE status='PROCESSING' AND lease_until<=clock_timestamp() AND attempts=6
                        ORDER BY lease_until LIMIT 100 FOR UPDATE SKIP LOCKED
                    )
                    UPDATE notification_email_outbox o SET status='FAILED',lease_token=NULL,lease_until=NULL,
                        last_failure='DELIVERY_UNCERTAIN',updated_at=clock_timestamp()
                    FROM expired e WHERE o.id=e.id
                    """);
            return jdbc.query("""
                    WITH candidate AS (
                        SELECT id FROM notification_email_outbox WHERE attempts<6 AND (
                            (status IN ('PENDING','RETRY_WAIT') AND next_attempt_at<=clock_timestamp())
                            OR (status='PROCESSING' AND lease_until<=clock_timestamp()))
                        ORDER BY next_attempt_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
                    )
                    UPDATE notification_email_outbox o SET status='PROCESSING',attempts=o.attempts+1,
                        lease_token=?,lease_until=clock_timestamp()+INTERVAL '2 minutes',updated_at=clock_timestamp()
                    FROM candidate c WHERE o.id=c.id
                    RETURNING o.id,o.event_id,o.recipient_id,o.notification_type,o.dedupe_key,o.lease_token,o.attempts,
                        o.created_at <= clock_timestamp() - (? * INTERVAL '1 second') AS expired
                    """, (row, number) -> new Delivery(row.getObject("id", UUID.class),
                    row.getObject("event_id", UUID.class), row.getObject("recipient_id", UUID.class),
                    NotificationType.valueOf(row.getString("notification_type")), row.getString("dedupe_key"),
                    row.getObject("lease_token", UUID.class), row.getInt("attempts"), row.getBoolean("expired")),
                    UUID.randomUUID(), maxAge.toSeconds()).stream().findFirst();
        });
    }

    /** The event the copy was planned from (read in the caller's transaction). */
    Optional<NotificationPlanner.Event> event(Delivery delivery) {
        return jdbc.query("""
                SELECT id, event_type, entity_type, entity_id, recipient_ids FROM notification_events WHERE id = ?
                """, (row, number) -> new NotificationPlanner.Event(row.getObject("id", UUID.class),
                NotificationType.valueOf(row.getString("event_type")),
                NotificationLink.Type.valueOf(row.getString("entity_type")), row.getObject("entity_id", UUID.class),
                NotificationWorker.uuids(row.getArray("recipient_ids"))), delivery.eventId()).stream().findFirst();
    }

    /** The recipient's current address and name, when the account may still receive staff email. */
    Optional<Recipient> recipient(Delivery delivery) {
        return jdbc.query("""
                SELECT email, full_name FROM user_accounts WHERE id = ? AND enabled AND admin_locked_at IS NULL
                """, (row, number) -> new Recipient(row.getString(1), row.getString(2)), delivery.recipientId())
                .stream().findFirst();
    }

    boolean renew(Delivery delivery) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            timeouts();
            return jdbc.update("""
                    UPDATE notification_email_outbox SET lease_until=clock_timestamp()+INTERVAL '2 minutes',
                        updated_at=clock_timestamp()
                    WHERE id=? AND status='PROCESSING' AND lease_token=? AND lease_until>clock_timestamp()
                    """, delivery.id(), delivery.leaseToken()) == 1;
        }));
    }

    boolean sent(Delivery delivery) { return finish(delivery, "SENT", null); }

    /** Not sent at all: the recipient, the state or the preference changed, or the copy is too old. */
    boolean cancel(Delivery delivery, String reason) {
        if (!CANCEL_REASONS.contains(reason)) throw new IllegalArgumentException("Invalid cancellation reason");
        return finish(delivery, "CANCELLED", reason);
    }

    boolean failed(Delivery delivery, boolean permanent, String failure) {
        if (permanent && !Set.of("RECIPIENT_REFUSED", "PAYLOAD_INVALID").contains(failure)) {
            throw new IllegalArgumentException("Invalid failure code");
        }
        if (permanent || delivery.attempts() >= 6) return finish(delivery, "FAILED", permanent ? failure : "RETRIES_EXHAUSTED");
        return Boolean.TRUE.equals(transactions.execute(status -> {
            timeouts();
            return jdbc.update("""
                    UPDATE notification_email_outbox SET status='RETRY_WAIT',last_failure='SMTP_TEMPORARY',
                        next_attempt_at=clock_timestamp()+(? * INTERVAL '1 second'),updated_at=clock_timestamp(),
                        lease_token=NULL,lease_until=NULL
                    WHERE id=? AND status='PROCESSING' AND lease_token=?
                    """, RETRY_SECONDS[delivery.attempts() - 1], delivery.id(), delivery.leaseToken()) == 1;
        }));
    }

    private boolean finish(Delivery delivery, String result, String failure) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            timeouts();
            return jdbc.update("""
                    UPDATE notification_email_outbox SET status=?,last_failure=?,updated_at=clock_timestamp(),
                        sent_at=CASE WHEN ?='SENT' THEN clock_timestamp() ELSE NULL END,lease_token=NULL,lease_until=NULL
                    WHERE id=? AND status='PROCESSING' AND lease_token=?
                    """, result, failure, result, delivery.id(), delivery.leaseToken()) == 1;
        }));
    }

    private void timeouts() {
        jdbc.execute("SET LOCAL lock_timeout='2s'");
        jdbc.execute("SET LOCAL statement_timeout='3s'");
    }

    record Delivery(UUID id, UUID eventId, UUID recipientId, NotificationType type, String dedupeKey, UUID leaseToken,
                    int attempts, boolean expired) {
    }

    record Recipient(String email, String name) {
        @Override public String toString() { return "NotificationRecipient[redacted]"; }
    }
}
