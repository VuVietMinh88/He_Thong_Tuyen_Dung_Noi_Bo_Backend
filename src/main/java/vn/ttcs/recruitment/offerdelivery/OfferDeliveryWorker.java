package vn.ttcs.recruitment.offerdelivery;

import vn.ttcs.recruitment.offer.OfferMailPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.MailPreparationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vn.ttcs.recruitment.common.MailFailureClassifier;

@Component
public class OfferDeliveryWorker {
    private static final Logger LOG = LoggerFactory.getLogger(OfferDeliveryWorker.class);
    private final OfferDeliverySettings settings;
    private final OfferOutboxRepository outbox;
    private final OfferDeliveryCrypto crypto;
    private final OfferDeliveryMailSender mail;

    public OfferDeliveryWorker(OfferDeliverySettings settings, OfferOutboxRepository outbox,
        OfferDeliveryCrypto crypto, OfferDeliveryMailSender mail) {
        this.settings = settings;
        this.outbox = outbox;
        this.crypto = crypto;
        this.mail = mail;
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
        try {
            mail.send(payload, settings.messageId(delivery.id()));
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
