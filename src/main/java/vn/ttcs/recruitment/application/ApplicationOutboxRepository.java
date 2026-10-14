package vn.ttcs.recruitment.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.UUID;

@Repository
public class ApplicationOutboxRepository {
    private static final long[] RETRY_SECONDS = {60,300,900,3600,21600};
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public ApplicationOutboxRepository(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc; transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(5);
    }

    // Joins the receipt transaction: never use REQUIRES_NEW for enqueue.
    void enqueue(UUID id, UUID applicationId, String messageId, ApplicationOutboxCrypto.Encrypted encrypted) {
        jdbc.update("""
                INSERT INTO application_email_outbox(id,application_id,message_id,key_id,nonce,ciphertext)
                VALUES (?,?,?,?,?,?)
                """,id,applicationId,messageId,encrypted.keyId(),encrypted.nonce(),encrypted.ciphertext());
    }

    Optional<Delivery> claim() {
        return transaction.execute(status -> {
            timeouts();
            jdbc.update("""
                    WITH expired AS (
                        SELECT id FROM application_email_outbox
                        WHERE status='PROCESSING' AND lease_until<=clock_timestamp() AND attempts=6
                        ORDER BY lease_until LIMIT 100 FOR UPDATE SKIP LOCKED
                    )
                    UPDATE application_email_outbox o SET status='FAILED',lease_token=NULL,lease_until=NULL,
                        last_failure='DELIVERY_UNCERTAIN',updated_at=clock_timestamp()
                    FROM expired e WHERE o.id=e.id
                    """);
            UUID token = UUID.randomUUID();
            var rows = jdbc.query("""
                    WITH candidate AS (
                        SELECT id FROM application_email_outbox WHERE attempts<6 AND (
                            (status IN ('PENDING','RETRY_WAIT') AND next_attempt_at<=clock_timestamp())
                            OR (status='PROCESSING' AND lease_until<=clock_timestamp()))
                        ORDER BY next_attempt_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
                    )
                    UPDATE application_email_outbox o SET status='PROCESSING',attempts=o.attempts+1,
                        lease_token=?,lease_until=clock_timestamp()+INTERVAL '2 minutes',updated_at=clock_timestamp()
                    FROM candidate c WHERE o.id=c.id
                    RETURNING o.id,o.application_id,o.message_id,o.key_id,o.nonce,o.ciphertext,o.lease_token,o.attempts
                    """, (row, number) -> new Delivery(row.getObject("id",UUID.class),row.getObject("application_id",UUID.class),
                    row.getString("message_id"),row.getString("key_id"),row.getBytes("nonce"),row.getBytes("ciphertext"),
                    row.getObject("lease_token",UUID.class),row.getInt("attempts")), token);
            return rows.stream().findFirst();
        });
    }

    boolean renew(Delivery delivery) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            timeouts();
            return jdbc.update("""
                    UPDATE application_email_outbox SET lease_until=clock_timestamp()+INTERVAL '2 minutes',updated_at=clock_timestamp()
                    WHERE id=? AND status='PROCESSING' AND lease_token=? AND lease_until>clock_timestamp()
                    """,delivery.id(),delivery.leaseToken()) == 1;
        }));
    }

    boolean sent(Delivery delivery) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            timeouts();
            return jdbc.update("""
                    UPDATE application_email_outbox SET status='SENT',sent_at=clock_timestamp(),updated_at=clock_timestamp(),
                        lease_token=NULL,lease_until=NULL,last_failure=NULL,key_id=NULL,nonce=NULL,ciphertext=NULL
                    WHERE id=? AND status='PROCESSING' AND lease_token=?
                    """, delivery.id(),delivery.leaseToken()) == 1;
        }));
    }

    boolean failed(Delivery delivery, boolean permanent, String failure) {
        boolean terminal = permanent || delivery.attempts() >= 6;
        String code = permanent ? failure : terminal ? "RETRIES_EXHAUSTED" : "SMTP_TEMPORARY";
        long delay = terminal ? 0 : RETRY_SECONDS[delivery.attempts()-1];
        return Boolean.TRUE.equals(transaction.execute(status -> {
            timeouts();
            return jdbc.update("""
                    UPDATE application_email_outbox SET status=?,last_failure=?,
                        next_attempt_at=clock_timestamp()+(? * INTERVAL '1 second'),updated_at=clock_timestamp(),
                        lease_token=NULL,lease_until=NULL
                    WHERE id=? AND status='PROCESSING' AND lease_token=?
                    """,terminal ? "FAILED" : "RETRY_WAIT",code,delay,delivery.id(),delivery.leaseToken()) == 1;
        }));
    }

    private void timeouts() {
        jdbc.execute("SET LOCAL lock_timeout='2s'"); jdbc.execute("SET LOCAL statement_timeout='3s'");
    }

    record Delivery(UUID id, UUID applicationId, String messageId, String keyId, byte[] nonce, byte[] ciphertext,
                    UUID leaseToken, int attempts) {
        @Override public String toString() { return "Delivery[personal data redacted]"; }
    }
}
