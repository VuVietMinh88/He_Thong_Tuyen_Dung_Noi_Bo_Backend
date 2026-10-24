package vn.ttcs.recruitment.messaging;

import org.springframework.http.HttpStatus;
import vn.ttcs.recruitment.common.ApiException;

import java.util.UUID;

/**
 * Task 539: queues a FAILED candidate email again, one implementation per outbox. The retry service calls it in its
 * write transaction after locking the caller, the requisition, the application and the message, and after checking
 * that the message is FAILED. An implementation locks its delivery row, checks the current business state and either
 * queues the email again (returning the delivery row now queued, the same row or a new one) or refuses with 409
 * EMAIL_RETRY_OBSOLETE (the email no longer makes sense or its recipient changed) or EMAIL_RETRY_NOT_POSSIBLE (the
 * failed content cannot be rebuilt safely). It never brings back a revoked or expired capability: a link is either
 * still valid, or a new one is issued and the old one stops working.
 */
public interface FailedEmailResender {
    EmailMessageLog.Delivery delivery();

    UUID resend(FailedEmail email);

    /** The failed message as the retry service locked it. */
    record FailedEmail(UUID messageId, EmailTemplateType type, String variant, UUID applicationId, UUID interviewId,
                       UUID offerId, UUID deliveryId, String recipientEmail, String lastFailure) {
        @Override public String toString() { return "FailedEmail[" + messageId + ", " + type + ", recipient redacted]"; }
    }

    static ApiException obsolete(String message) {
        return new ApiException(HttpStatus.CONFLICT, "EMAIL_RETRY_OBSOLETE", message);
    }

    static ApiException notPossible(String message) {
        return new ApiException(HttpStatus.CONFLICT, "EMAIL_RETRY_NOT_POSSIBLE", message);
    }
}
