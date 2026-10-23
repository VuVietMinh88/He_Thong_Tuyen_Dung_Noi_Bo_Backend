package vn.ttcs.recruitment.offerdelivery;

import vn.ttcs.recruitment.offer.OfferMailPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.MailPreparationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vn.ttcs.recruitment.common.MailFailureClassifier;
import vn.ttcs.recruitment.messaging.EmailTemplate;
import vn.ttcs.recruitment.messaging.EmailTemplateType;
import vn.ttcs.recruitment.messaging.EmailTemplates;
import vn.ttcs.recruitment.messaging.TemplateVariable;

import java.util.Map;

@Component
public class OfferDeliveryWorker {
    private static final Logger LOG = LoggerFactory.getLogger(OfferDeliveryWorker.class);
    private final OfferDeliverySettings settings;
    private final OfferOutboxRepository outbox;
    private final OfferDeliveryCrypto crypto;
    private final OfferDeliveryMailSender mail;
    private final EmailTemplates templates;

    // Task 533: the OFFER template current at send time gives the subject and the opening.
    public OfferDeliveryWorker(OfferDeliverySettings settings, OfferOutboxRepository outbox,
        OfferDeliveryCrypto crypto, OfferDeliveryMailSender mail, EmailTemplates templates) {
        this.settings = settings;
        this.outbox = outbox;
        this.crypto = crypto;
        this.mail = mail;
        this.templates = templates;
    }
    @Scheduled(initialDelayString = "${app.offers.poll-delay-ms:60000}",
        fixedDelayString = "${app.offers.poll-delay-ms:60000}")
    public void poll() {
        try {
            for (int i = 0; i < 10 && processNext(); i++) {
                /* One bounded batch; no SMTP in database transactions. */
            }
        }
        catch (RuntimeException exception) {
            LOG.warn("Offer delivery processing failed; will retry on the next run");
        }
    }

    public boolean processNext() {
        if (!settings.enabled() || !settings.deliveryEnabled() || !settings.ready()) return false;
        var claimed = outbox.claim();
        if (claimed.isEmpty()) return false;
        var delivery = claimed.get();
        OfferMailPayload payload;
        try {
            payload = crypto.decrypt(delivery.id(), delivery.offerId(), delivery.keyId(),
                delivery.nonce(), delivery.ciphertext());
            if (payload == null) throw new IllegalArgumentException("Invalid encrypted offer");
        }
        catch (RuntimeException exception) {
            outbox.failed(delivery, true, "PAYLOAD_INVALID");
            return true;
        }
        String unavailable = outbox.unavailableReason(delivery, payload);
        if (unavailable != null) {
            outbox.abandon(delivery, unavailable);
            return true;
        }
        if (!outbox.renew(delivery)) return true;
        // A fresh business-time check after the lease lock may have waited. SMTP itself remains outside locks.
        unavailable = outbox.unavailableReason(delivery, payload);
        if (unavailable != null) {
            outbox.abandon(delivery, unavailable);
            return true;
        }
        EmailTemplate.Rendered template = templates.render(EmailTemplateType.OFFER, Map.of(
            TemplateVariable.CANDIDATE_NAME, payload.recipientName(),
            TemplateVariable.POSITION_TITLE, payload.positionTitle()));
        try {
            mail.send(payload, settings.messageId(delivery.id()), template);
        }
        catch (MailPreparationException exception) {
            outbox.failed(delivery, true, "PAYLOAD_INVALID");
            return true;
        }
        catch (MailException exception) {
            outbox.failed(delivery, MailFailureClassifier.refusedRecipient(exception), "RECIPIENT_REFUSED");
            return true;
        }
        outbox.sent(delivery);
        return true;
    }
}
