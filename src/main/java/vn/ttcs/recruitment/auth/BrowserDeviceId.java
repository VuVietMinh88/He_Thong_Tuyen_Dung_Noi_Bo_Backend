package vn.ttcs.recruitment.auth;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The X-Device-Id header: a random UUID the frontend keeps for one browser, so per-browser limits apply without
 * knowing who uses the browser. Only the canonical 8-4-4-4-12 hexadecimal text is accepted, because
 * {@link UUID#fromString(String)} also takes shortened forms such as "1-1-1-1-1".
 */
public final class BrowserDeviceId {

    public static final String HEADER = "X-Device-Id";

    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private BrowserDeviceId() { }

    /** The browser UUID, or empty when the header is missing or is not canonical UUID text. */
    public static Optional<UUID> parse(String header) {
        if (header == null || !UUID_TEXT.matcher(header).matches()) {
            return Optional.empty();
        }
        return Optional.of(UUID.fromString(header));
    }
}
