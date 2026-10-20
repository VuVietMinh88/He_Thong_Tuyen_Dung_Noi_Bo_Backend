package vn.ttcs.recruitment.messaging;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.UUID;

/**
 * Task 538: settings of the candidate mail queue. Delivery can be switched off (CANDIDATE_MAIL_DELIVERY_ENABLED=false)
 * without losing queued emails; they wait until it is on again. The emails carry no secret, so there is no key.
 */
@Component
public class CandidateMailSettings {
    private final boolean deliveryEnabled;
    private final String from;
    private final String messageDomain;
    private final ZoneId zone;

    public CandidateMailSettings(@Value("${app.candidate-mail.delivery-enabled:true}") boolean deliveryEnabled,
                                 @Value("${app.mail.from}") String from,
                                 @Value("${app.business-zone}") ZoneId zone) {
        try {
            if (from.length() > 254 || from.indexOf('\r') >= 0 || from.indexOf('\n') >= 0) throw new AddressException();
            InternetAddress[] addresses = InternetAddress.parse(from, true);
            if (addresses.length != 1) throw new AddressException();
            addresses[0].validate();
            String address = addresses[0].getAddress();
            messageDomain = address.substring(address.lastIndexOf('@') + 1);
        } catch (AddressException failure) {
            throw new IllegalArgumentException("MAIL_FROM must contain one valid mailbox");
        }
        this.deliveryEnabled = deliveryEnabled;
        this.from = from;
        this.zone = zone;
    }

    public boolean deliveryEnabled() { return deliveryEnabled; }
    public String from() { return from; }
    public ZoneId zone() { return zone; }
    /** Stable per queue row, so a retried SMTP hand-over of the same row can be recognised as the same email. */
    public String messageId(UUID outboxId) { return "<candidate-mail." + outboxId + "@" + messageDomain + ">"; }
}
