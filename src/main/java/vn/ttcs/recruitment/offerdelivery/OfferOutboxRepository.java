package vn.ttcs.recruitment.offerdelivery;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.offer.OfferMailPayload;
import java.time.Clock;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Fenced short transactions. SMTP is deliberately outside these transactions. */
@Repository
public class OfferOutboxRepository {
    private static final long[] RETRY_SECONDS = {
        60, 300, 900, 3600, 21600
    };
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public OfferOutboxRepository(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transactions.setTimeout(5);
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(UUID id, UUID offer, UUID application, long version, UUID token, OfferDeliveryCrypto.Encrypted encrypted) {
        jdbc.update("""
                INSERT INTO offer_delivery_outbox(id,offer_id,application_id,offer_version,token_id,key_id,nonce,ciphertext)
                VALUES (?,?,?,?,?,?,?,?)
                """, id, offer, application, version, token, encrypted.keyId(), encrypted.nonce(), encrypted.ciphertext());
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelPending(UUID offer) {
        jdbc.update("""
                UPDATE offer_delivery_outbox SET status='CANCELLED',last_failure='OBSOLETE',key_id=NULL,nonce=NULL,
                    ciphertext=NULL,lease_token=NULL,lease_until=NULL,updated_at=clock_timestamp()
                WHERE offer_id=? AND status IN ('PENDING','PROCESSING','RETRY_WAIT')
                """, offer);
    }
    Optional<Delivery> claim() {
        return transactions.execute(tx -> {
            timeouts();
            jdbc.update("""
                    WITH expired AS (SELECT id FROM offer_delivery_outbox
                        WHERE status='PROCESSING' AND lease_until<=clock_timestamp() AND attempts=6
                        ORDER BY lease_until LIMIT 100 FOR UPDATE SKIP LOCKED)
                    UPDATE offer_delivery_outbox o SET status='FAILED',lease_token=NULL,lease_until=NULL,
                        last_failure='DELIVERY_UNCERTAIN',key_id=NULL,nonce=NULL,ciphertext=NULL,updated_at=clock_timestamp()
                    FROM expired e WHERE o.id=e.id
                    """);
            return jdbc.query("""
                    WITH candidate AS (SELECT id FROM offer_delivery_outbox WHERE attempts<6 AND (
                        (status IN ('PENDING','RETRY_WAIT') AND next_attempt_at<=clock_timestamp())
                        OR (status='PROCESSING' AND lease_until<=clock_timestamp()))
                        ORDER BY next_attempt_at,id LIMIT 1 FOR UPDATE SKIP LOCKED)
                    UPDATE offer_delivery_outbox o SET status='PROCESSING',attempts=o.attempts+1,
                        lease_token=?,lease_until=clock_timestamp()+INTERVAL '2 minutes',updated_at=clock_timestamp()
                    FROM candidate c WHERE o.id=c.id RETURNING o.*
                    """, (row, number) -> new Delivery(row.getObject("id", UUID.class), row.getObject("offer_id", UUID.class),
                row.getObject("application_id", UUID.class), row.getLong("offer_version"), row.getObject("token_id", UUID.class),
                row.getString("key_id"), row.getBytes("nonce"), row.getBytes("ciphertext"),
                row.getObject("lease_token", UUID.class), row.getInt("attempts")), UUID.randomUUID()).stream().findFirst();
        });
    }
    String unavailableReason(Delivery delivery, OfferMailPayload payload) {
        return transactions.execute(tx -> {
            timeouts();
            var rows = jdbc.query("""
                    SELECT o.*,a.email AS current_email,a.full_name AS current_name,ca.candidate_id AS current_candidate,
                        a.stage,r.status AS requisition_status,r.position_id AS current_position,
                        t.revoked_at,t.expires_at,p.active AS position_active
                    FROM offers o JOIN job_applications a ON a.id=o.application_id
                    JOIN candidate_applications ca ON ca.application_id=a.id
                    JOIN recruitment_requisitions r ON r.id=a.requisition_id
                    JOIN positions p ON p.id=r.position_id
                    JOIN offer_response_tokens t ON t.id=? AND t.offer_id=o.id AND t.application_id=o.application_id
                    WHERE o.id=? AND o.application_id=?
                    """, (r, number) -> {
                boolean alive = "SENT".equals(r.getString("status")) && r.getLong("version") == delivery.version()
                && "OFFER".equals(r.getString("stage")) && "APPROVED".equals(r.getString("requisition_status"))
                && r.getTimestamp("revoked_at") == null && r.getTimestamp("expires_at").toInstant().isAfter(clock.instant())
                && r.getTimestamp("response_deadline").toInstant().isAfter(clock.instant())
                && r.getBoolean("position_active") && Objects.equals(r.getObject("position_id"), r.getObject("current_position"));
                if (!alive) return "OBSOLETE";
                if (!Objects.equals(r.getString("email"), r.getString("current_email"))
                    || !Objects.equals(r.getString("candidate_name"), r.getString("current_name"))
                    || !Objects.equals(r.getObject("candidate_id"), r.getObject("current_candidate"))
                    || !Objects.equals(payload.email(), r.getString("email"))) return "RECIPIENT_UNAVAILABLE";
                // Encrypted content must match the approved frozen terms, even if a database row was altered manually.
                if (!Objects.equals(payload.recipientName(), r.getString("candidate_name"))
                    || !Objects.equals(payload.positionTitle(), r.getString("position_title"))
                    || payload.salary() != r.getLong("salary") || payload.allowance() != r.getLong("allowance")
                    || !Objects.equals(payload.startDate(), r.getDate("start_date").toLocalDate())
                    || !Objects.equals(payload.responseDeadline(), r.getTimestamp("response_deadline").toInstant())) return "OBSOLETE";
                return "AVAILABLE";
            }, delivery.tokenId(), delivery.offerId(), delivery.applicationId());
            if (rows.isEmpty()) return "OBSOLETE";
            return "AVAILABLE".equals(rows.getFirst()) ? null : rows.getFirst();
        });
    }
    boolean renew(Delivery delivery) {
        return Boolean.TRUE.equals(transactions.execute(tx -> {
            timeouts();
            return jdbc.update("""
                    UPDATE offer_delivery_outbox queued SET lease_until=clock_timestamp()+INTERVAL '2 minutes',updated_at=clock_timestamp()
                    WHERE queued.id=? AND queued.status='PROCESSING' AND queued.lease_token=? AND queued.lease_until>clock_timestamp()
                        AND EXISTS (SELECT 1 FROM offers o JOIN job_applications a ON a.id=o.application_id
                            JOIN candidate_applications ca ON ca.application_id=a.id
                            JOIN recruitment_requisitions r ON r.id=a.requisition_id JOIN positions p ON p.id=r.position_id
                            JOIN offer_response_tokens t ON t.id=queued.token_id AND t.offer_id=o.id AND t.application_id=a.id
                            WHERE o.id=queued.offer_id AND o.application_id=queued.application_id AND o.version=queued.offer_version
                                AND o.status='SENT' AND a.stage='OFFER' AND r.status='APPROVED' AND p.active
                                AND r.position_id=o.position_id AND a.email=o.email AND a.full_name=o.candidate_name
                                AND ca.candidate_id=o.candidate_id AND t.revoked_at IS NULL
                                AND t.expires_at>? AND o.response_deadline>?)
                    """, delivery.id(), delivery.leaseToken(), Timestamp.from(clock.instant()), Timestamp.from(clock.instant())) == 1;
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
        if (permanent && !Set.of("RECIPIENT_REFUSED", "PAYLOAD_INVALID").contains(failure)) throw new IllegalArgumentException("Invalid failure code");
        if (permanent || delivery.attempts() >= 6) return finish(delivery, "FAILED", permanent ? failure : "RETRIES_EXHAUSTED");
        return Boolean.TRUE.equals(transactions.execute(tx -> {
            timeouts();
            return jdbc.update("""
                    UPDATE offer_delivery_outbox SET status='RETRY_WAIT',last_failure='SMTP_TEMPORARY',
                        next_attempt_at=clock_timestamp()+(? * INTERVAL '1 second'),updated_at=clock_timestamp(),lease_token=NULL,lease_until=NULL
                    WHERE id=? AND status='PROCESSING' AND lease_token=? AND lease_until>clock_timestamp()
                    """, RETRY_SECONDS[delivery.attempts()-1], delivery.id(), delivery.leaseToken()) == 1;
        }));
    }

    private boolean finish(Delivery delivery, String state, String failure) {
        return Boolean.TRUE.equals(transactions.execute(tx -> {
            timeouts();
            return jdbc.update("""
                    UPDATE offer_delivery_outbox SET status=?,last_failure=?,updated_at=clock_timestamp(),
                        sent_at=CASE WHEN ?='SENT' THEN clock_timestamp() ELSE NULL END,
                        lease_token=NULL,lease_until=NULL,key_id=NULL,nonce=NULL,ciphertext=NULL
                    WHERE id=? AND status='PROCESSING' AND lease_token=? AND lease_until>clock_timestamp()
                    """, state, failure, state, delivery.id(), delivery.leaseToken()) == 1;
        }));
    }

    private void timeouts() {
        jdbc.execute("SET LOCAL lock_timeout='2s'");
        jdbc.execute("SET LOCAL statement_timeout='3s'");
    }
    record Delivery(UUID id, UUID offerId, UUID applicationId, long version, UUID tokenId, String keyId,
        byte[] nonce, byte[] ciphertext, UUID leaseToken, int attempts) {
        @Override public String toString() {
            return "OfferDelivery[redacted]";
        }
    }
}
