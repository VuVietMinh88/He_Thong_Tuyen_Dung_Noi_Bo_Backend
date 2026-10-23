package vn.ttcs.recruitment.messaging;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * Task 533: records each candidate email in email_messages (V66_1), in the transaction of the event that decides it,
 * so the decision commits or rolls back with the business change. A queued email names its delivery row; a
 * suppressed one (automatic sending off) has none and is never sent. The dedupe key makes an idempotent repeat of the
 * event record nothing the second time.
 */
@Component
public class EmailMessageLog {
    /** The outbox that delivers an email (email_messages.delivery). */
    public enum Delivery { APPLICATION_CONFIRMATION, INTERVIEW_INVITATION, OFFER_DELIVERY, CANDIDATE_MAIL }

    public enum Trigger { AUTOMATIC, MANUAL }

    /** One email to record. variant is set for INTERVIEW_INVITATION only; createdBy for an email a person sent. */
    public record Message(EmailTemplateType type, String variant, UUID applicationId, UUID interviewId, UUID offerId,
                          Trigger trigger, String recipientEmail, String dedupeKey, UUID createdBy) {
        public Message {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(applicationId, "applicationId");
            Objects.requireNonNull(trigger, "trigger");
            Objects.requireNonNull(recipientEmail, "recipientEmail");
            if (dedupeKey == null || dedupeKey.isBlank() || dedupeKey.length() > 200) {
                throw new IllegalArgumentException("An email message needs a dedupe key of at most 200 characters");
            }
        }

        @Override public String toString() { return "EmailMessage[" + type + ", " + applicationId + ", recipient redacted]"; }
    }

    private final JdbcTemplate jdbc;
    private final EmailTemplates templates;
    private final Clock clock;

    public EmailMessageLog(JdbcTemplate jdbc, EmailTemplates templates, Clock clock) {
        this.jdbc = jdbc;
        this.templates = templates;
        this.clock = clock;
    }

    /** Whether the event of this type sends its email automatically now (read in the caller's transaction). */
    public boolean autoSend(EmailTemplateType type) {
        return templates.autoSend(type);
    }

    /**
     * Whether the application has a rejection letter queued, being sent or sent (automatic or manual), other than the
     * message except (null: any). These are the statuses of email_messages_one_rejection_idx: a second live letter
     * would violate it. Callers hold the application row lock, which every writer of a rejection letter takes first.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean liveRejection(UUID applicationId, UUID except) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM email_messages WHERE application_id = ? AND template_type = 'REJECTION'
                    AND status IN ('QUEUED', 'SENDING', 'RETRY_WAIT', 'SENT') AND id IS DISTINCT FROM CAST(? AS uuid))
                """, Boolean.class, applicationId, except));
    }

    /**
     * The email is queued in delivery as the row deliveryId, which the caller writes in the same transaction (since
     * task 538 every queued email names its row). Null when this email was already recorded.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID queued(Message message, Delivery delivery, UUID deliveryId) {
        Objects.requireNonNull(delivery, "delivery");
        Objects.requireNonNull(deliveryId, "deliveryId");
        return insert(message, "QUEUED", delivery.name(), deliveryId);
    }

    /** Automatic sending of the type is off: recorded as SUPPRESSED, never queued. Null when already recorded. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID suppressed(Message message) {
        return insert(message, "SUPPRESSED", null, null);
    }

    private UUID insert(Message message, String status, String delivery, UUID deliveryId) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MICROS));
        int inserted = jdbc.update("""
                INSERT INTO email_messages(id, template_type, variant, application_id, interview_id, offer_id,
                    trigger_kind, status, delivery, delivery_id, recipient_email, dedupe_key, created_by, created_at,
                    updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT (dedupe_key) DO NOTHING
                """, id, message.type().name(), message.variant(), message.applicationId(), message.interviewId(),
                message.offerId(), message.trigger().name(), status, delivery, deliveryId, message.recipientEmail(),
                message.dedupeKey(), message.createdBy(), now, now);
        return inserted == 1 ? id : null;
    }
}
