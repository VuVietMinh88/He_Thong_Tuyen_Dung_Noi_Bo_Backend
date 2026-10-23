package vn.ttcs.recruitment.application;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;

@Component
public class ApplicationConfirmationSettings {
    private final URI trackingPage;
    private final String from;
    private final String domain;
    private final String keyId;
    private final SecretKey key;

    public ApplicationConfirmationSettings(@Value("${app.application-confirmation.page-url}") URI trackingPage,
            @Value("${app.mail.from}") String from,
            @Value("${app.application-confirmation.encryption-key:}") String encodedKey,
            @Value("${app.application-confirmation.key-id:v1}") String keyId) {
        boolean localHttp = "http".equals(trackingPage.getScheme()) && Set.of("localhost","127.0.0.1","[::1]")
                .contains(trackingPage.getHost() == null ? "" : trackingPage.getHost());
        if (trackingPage.getHost() == null || trackingPage.getUserInfo() != null || trackingPage.getRawQuery() != null
                || trackingPage.getRawFragment() != null || !("https".equals(trackingPage.getScheme()) || localHttp)) {
            throw new IllegalArgumentException("APPLICATION_TRACKING_PAGE_URL must be fixed HTTPS (HTTP only on loopback), without credentials, query or fragment");
        }
        try {
            if (from.indexOf('\r') >= 0 || from.indexOf('\n') >= 0 || from.length() > 254) throw new AddressException();
            var addresses = InternetAddress.parse(from, true);
            if (addresses.length != 1) throw new AddressException();
            addresses[0].validate();
            String address = addresses[0].getAddress();
            domain = address.substring(address.lastIndexOf('@') + 1);
            if (!domain.matches("[A-Za-z0-9.-]{1,190}")) throw new AddressException();
        } catch (AddressException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("MAIL_FROM must contain one valid mailbox");
        }
        if (!keyId.matches("[A-Za-z0-9_-]{1,32}")) throw new IllegalArgumentException("Invalid confirmation encryption key ID");
        this.trackingPage = trackingPage; this.from = from; this.keyId = keyId;
        if (encodedKey.isBlank()) key = null;
        else {
            byte[] raw;
            try { raw = Base64.getDecoder().decode(encodedKey); }
            catch (IllegalArgumentException exception) { throw new IllegalArgumentException("Confirmation encryption key must be Base64 of 32 random bytes"); }
            try {
                if (raw.length != 32) throw new IllegalArgumentException("Confirmation encryption key must contain 32 bytes");
                key = new SecretKeySpec(raw, "AES");
            } finally { Arrays.fill(raw, (byte) 0); }
        }
    }

    public boolean ready() { return key != null; }
    String from() { return from; }
    String keyId() { return keyId; }
    SecretKey key() {
        if (!ready()) throw new IllegalStateException("Confirmation encryption is not configured");
        return key;
    }
    String trackingUrl(String code) { return trackingPage.toASCIIString() + "#ma=" + code; }
    // Task 344 (story S4-06): the withdrawal link of the confirmation email is the same validated tracking page with
    // the same code, under "#rut=" instead of "#ma=": the page opens its withdrawal mode, asks the email and a second
    // confirmation, and never acts on page load (mail scanners open links). A fragment never reaches a server, a proxy
    // or a Referer, and the link carries no second secret: the withdrawal (task 345) checks the code and the email
    // again under the row lock (ApplicationWithdrawalAccess). The mail paragraph ships with that endpoint, not here.
    String withdrawalUrl(String code) { return trackingPage.toASCIIString() + "#rut=" + code; }
    String messageId(java.util.UUID outboxId) { return "<application-confirmation." + outboxId + "@" + domain + ">"; }
    @Override public String toString() { return "ApplicationConfirmationSettings[secrets redacted]"; }
}
