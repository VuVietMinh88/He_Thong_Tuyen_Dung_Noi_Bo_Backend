package vn.ttcs.recruitment.offerdelivery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Core offers exist without SMTP; expiration must therefore run independently of delivery configuration. */
@Component
public class OfferExpiryWorker {
    private static final Logger LOG = LoggerFactory.getLogger(OfferExpiryWorker.class);
    private final OfferDeliveryService service;

    public OfferExpiryWorker(OfferDeliveryService service) {
        this.service = service;
    }
    @Scheduled(initialDelayString = "${app.offers.expiry-delay-ms:60000}", fixedDelayString = "${app.offers.expiry-delay-ms:60000}")
    public void poll() {
        try {
            service.expireInactive();
        }
        catch(RuntimeException failure) {
            LOG.warn("Offer expiration will retry on the next run");
        }
    }
}
