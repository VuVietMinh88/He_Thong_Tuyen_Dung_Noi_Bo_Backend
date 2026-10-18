package vn.ttcs.recruitment.offerdelivery;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import vn.ttcs.recruitment.common.ApiException;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

/** Opt-in feature and separate delivery switch; never creates a secret at application startup. */
@Component
public class OfferDeliverySettings {
    private final boolean enabled;
    private final boolean deliveryEnabled;
    private final URI pageUrl;
    private final String from;
    private final String messageDomain;
    private final String keyId;
    private final SecretKey key;
    private final ZoneId zone;

    public OfferDeliverySettings(
        @Value("${app.offers.enabled:false}") boolean enabled,
        @Value("${app.offers.delivery-enabled:false}") boolean deliveryEnabled,
        @Value("${app.offers.page-url:http://localhost:8080/api/v1/public/offers/reply}") URI pageUrl,
        @Value("${app.mail.from}") String from,
        @Value("${app.offers.key-id:v1}") String keyId,
        @Value("${app.offers.encryption-key:}") String encodedKey,
        @Value("${app.offers.time-zone:Asia/Ho_Chi_Minh}") String timeZone) {
        boolean local = "http".equals(pageUrl.getScheme())
        && Set.of("localhost", "127.0.0.1", "[::1]").contains(pageUrl.getHost() == null ? "" : pageUrl.getHost());
        if (pageUrl.getHost() == null || pageUrl.getUserInfo() != null || pageUrl.getRawQuery() != null
            || pageUrl.getRawFragment() != null || !("https".equals(pageUrl.getScheme()) || local)) {
            throw new IllegalArgumentException("Offer delivery page must be fixed HTTPS, or loopback HTTP, without credentials/query/fragment");
        }
        try {
            if (from.length() > 254 || from.indexOf('\r') >= 0 || from.indexOf('\n') >= 0) throw new AddressException();
            InternetAddress[] addresses = InternetAddress.parse(from, true);
            if (addresses.length != 1) throw new AddressException();
            addresses[0].validate();
            String address = addresses[0].getAddress();
            messageDomain = address.substring(address.lastIndexOf('@') + 1);
        }
        catch (AddressException failure) {
            throw new IllegalArgumentException("MAIL_FROM must contain one valid mailbox");
        }
        if (!keyId.matches("[A-Za-z0-9_-]{1,32}")) throw new IllegalArgumentException("Invalid offer delivery key ID");
        this.enabled = enabled;
        this.deliveryEnabled = deliveryEnabled;
        this.pageUrl = pageUrl;
        this.from = from;
        this.keyId = keyId;
        this.zone = ZoneId.of(timeZone);
        if (encodedKey.isBlank()) key = null;
        else {
            byte[] raw;
            try {
                raw = Base64.getDecoder().decode(encodedKey);
            }
            catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException("Offer delivery key must be Base64 of 32 random bytes");
            }
            try {
                if (raw.length != 32) throw new IllegalArgumentException("Offer delivery key must contain 32 bytes");
                key = new SecretKeySpec(raw, "AES");
            }
            finally {
                Arrays.fill(raw, (byte) 0);
            }
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean deliveryEnabled() {
        return deliveryEnabled;
    }

    public boolean ready() {
        return enabled && key != null;
    }

    public void requireReady() {
        if (!ready()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "OFFER_UNAVAILABLE",
            "Chức năng gửi offer chưa được cấu hình. Vui lòng thử lại sau.");
    }

    public String keyId() {
        return keyId;
    }

    public SecretKey key() {
        requireReady();
        return key;
    }

    public URI pageUrl() {
        return pageUrl;
    }

    public String link(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw new IllegalArgumentException("Invalid offer credential");
        return pageUrl.toASCIIString() + "#token=" + token;
    }

    public String from() {
        return from;
    }

    public String messageId(UUID outboxId) {
        return "<offer-delivery." + outboxId + "@" + messageDomain + ">";
    }

    public ZoneId zone() {
        return zone;
    }
    @Override public String toString() {
        return "OfferDeliverySettings[secrets redacted]";
    }
}
