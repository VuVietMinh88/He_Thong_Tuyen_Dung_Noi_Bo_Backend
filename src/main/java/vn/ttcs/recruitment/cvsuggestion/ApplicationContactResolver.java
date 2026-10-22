package vn.ttcs.recruitment.cvsuggestion;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Internal contract for future recruitment mail consumers; caller must authorize before taking the snapshot. */
@Service
public class ApplicationContactResolver {
    private final ConfirmedContactRepository contacts;
    private final JdbcTemplate jdbc;
    public ApplicationContactResolver(ConfirmedContactRepository contacts,JdbcTemplate jdbc) { this.contacts=contacts;this.jdbc=jdbc; }
    public record MailSnapshot(UUID applicationId,ContactValues contact,long revision,Instant capturedAt) {
        @Override public String toString() { return "MailSnapshot[applicationId="+applicationId+", revision="+revision+", personal data redacted]"; }
    }
    /** Persist the returned values into the new mail's outbox in this SAME transaction, before releasing locks. */
    @Transactional(propagation=Propagation.MANDATORY)
    public MailSnapshot snapshotForNewRecruitmentMail(UUID applicationId) {
        // A pre-existing REPEATABLE_READ snapshot can hide a confirmation committed before we acquired the lock.
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection->{
            if (connection.getTransactionIsolation()!=java.sql.Connection.TRANSACTION_READ_COMMITTED)
                throw new org.springframework.transaction.IllegalTransactionStateException("Mail snapshots require READ_COMMITTED");
            return null;
        });
        var app=contacts.application(applicationId,true);
        var view=contacts.view(app);
        return new MailSnapshot(applicationId,view.effective(),view.revision(),
                jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant());
    }
}
