package vn.ttcs.recruitment.application;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

// The receipt writer creates this inside its storage/outbox transaction, before committing.
public record ApplicationReceipt(UUID applicationId, Instant receivedAt, String trackingCode, String trackingUrl) {
    public ApplicationReceipt {
        Objects.requireNonNull(applicationId);
        Objects.requireNonNull(receivedAt);
        if (trackingCode == null || !trackingCode.matches("[A-Za-z0-9_-]{43}")) {
            throw new IllegalArgumentException("Invalid application receipt");
        }
        URI url = URI.create(Objects.requireNonNull(trackingUrl));
        boolean localHttp = "http".equals(url.getScheme()) && Set.of("localhost", "127.0.0.1", "[::1]")
                .contains(url.getHost() == null ? "" : url.getHost());
        if (url.getHost() == null || url.getUserInfo() != null || url.getRawQuery() != null
                || !("https".equals(url.getScheme()) || localHttp) || !("ma=" + trackingCode).equals(url.getFragment())) {
            throw new IllegalArgumentException("Invalid application receipt");
        }
    }

    @Override public String toString() { return "ApplicationReceipt[tracking data redacted]"; }
}
