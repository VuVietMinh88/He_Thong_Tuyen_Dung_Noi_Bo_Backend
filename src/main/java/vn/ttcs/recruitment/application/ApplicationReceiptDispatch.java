package vn.ttcs.recruitment.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.ttcs.recruitment.messaging.EmailMessageLog;
import vn.ttcs.recruitment.messaging.EmailTemplateType;

import java.util.UUID;

/**
 * Task 533 (story S8-01, "Bật hoặc tắt gửi tự động cho từng loại"): the receipt of a new application, decided in the
 * transaction that stores it (public intake and staff referral alike). With automatic sending on, the encrypted
 * payload is queued in application_email_outbox as before and recorded in email_messages; with it off, nothing is
 * queued and the email is recorded as SUPPRESSED. The tracking code the HTTP response shows is unaffected.
 */
@Component
class ApplicationReceiptDispatch {
    private final ApplicationOutboxRepository outbox;
    private final ApplicationOutboxCrypto crypto;
    private final ApplicationConfirmationSettings settings;
    private final EmailMessageLog messages;

    ApplicationReceiptDispatch(ApplicationOutboxRepository outbox, ApplicationOutboxCrypto crypto,
                               ApplicationConfirmationSettings settings, EmailMessageLog messages) {
        this.outbox = outbox;
        this.crypto = crypto;
        this.settings = settings;
        this.messages = messages;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void decide(UUID applicationId, ApplicationConfirmationPayload payload) {
        var message = new EmailMessageLog.Message(EmailTemplateType.APPLICATION_RECEIPT, null, applicationId, null, null,
                EmailMessageLog.Trigger.AUTOMATIC, payload.email(), "APPLICATION_RECEIPT:" + applicationId, null);
        if (!messages.autoSend(EmailTemplateType.APPLICATION_RECEIPT)) {
            messages.suppressed(message);
            return;
        }
        UUID outboxId = UUID.randomUUID();
        outbox.enqueue(outboxId, applicationId, settings.messageId(outboxId), crypto.encrypt(outboxId, applicationId, payload));
        messages.queued(message, EmailMessageLog.Delivery.APPLICATION_CONFIRMATION, outboxId);
    }
}
