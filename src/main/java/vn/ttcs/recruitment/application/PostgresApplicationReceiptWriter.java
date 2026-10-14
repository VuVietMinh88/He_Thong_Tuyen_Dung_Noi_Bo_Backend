package vn.ttcs.recruitment.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

@Component
public class PostgresApplicationReceiptWriter implements ApplicationReceiptWriter {
    private final ApplicationStorageService storage;
    private final ApplicationOutboxRepository outbox;
    private final ApplicationOutboxCrypto crypto;
    private final ApplicationConfirmationSettings settings;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final SecureRandom random = new SecureRandom();

    public PostgresApplicationReceiptWriter(ApplicationStorageService storage, ApplicationOutboxRepository outbox,
            ApplicationOutboxCrypto crypto, ApplicationConfirmationSettings settings, JdbcTemplate jdbc,
            PlatformTransactionManager manager) {
        this.storage=storage;this.outbox=outbox;this.crypto=crypto;this.settings=settings;this.jdbc=jdbc;
        transaction=new TransactionTemplate(manager);
        // A caller's transaction must not let an uncommitted receipt escape.
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override public boolean ready() { return settings.ready(); }

    @Override public ApplicationReceipt accept(UUID postingId, ApplicationSubmission submission, ValidatedCv cv) {
        if (!ready()) throw ApplicationIntakeAvailability.unavailable();
        byte[] token=new byte[32];random.nextBytes(token);
        try {
            String code=Base64.getUrlEncoder().withoutPadding().encodeToString(token);
            byte[] hash=MessageDigest.getInstance("SHA-256").digest(token);
            return Objects.requireNonNull(transaction.execute(status -> {
                var stored=storage.store(postingId,submission,cv,hash);
                String title=jdbc.queryForObject("SELECT posting_title FROM job_applications WHERE id=?",String.class,stored.id());
                var receipt=new ApplicationReceipt(stored.id(),stored.receivedAt(),code,settings.trackingUrl(code));
                UUID outboxId=UUID.randomUUID();
                var payload=new ApplicationConfirmationPayload(submission.email(),submission.fullName(),title,
                        receipt.receivedAt(),code,receipt.trackingUrl(),settings.from());
                outbox.enqueue(outboxId,stored.id(),settings.messageId(outboxId),crypto.encrypt(outboxId,stored.id(),payload));
                return receipt;
            }));
        } catch(NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 unavailable"); }
        finally {Arrays.fill(token,(byte)0);}
    }
}
