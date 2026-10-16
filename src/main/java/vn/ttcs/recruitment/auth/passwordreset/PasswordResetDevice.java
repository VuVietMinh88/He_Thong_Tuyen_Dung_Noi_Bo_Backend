package vn.ttcs.recruitment.auth.passwordreset;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Forgot-password requests of one browser, shared across emails (table password_reset_devices). A window starts at
 * the first request counted in it; the fifth request of a window locks the browser for 15 minutes. Requests refused
 * while it is locked are not counted, so they never extend the lock.
 */
public record PasswordResetDevice(UUID id, int requestCount, Instant windowStartedAt, Instant lockedUntil) {
    public static final int MAX_REQUESTS = 5;
    public static final Duration WINDOW = Duration.ofMinutes(15);
    public static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    public boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    /** Whole seconds until the lock ends, rounded up and at least 1, for the Retry-After header. */
    public long retryAfterSeconds(Instant now) {
        long milliseconds = Duration.between(now, lockedUntil).toMillis();
        return Math.max(1, (milliseconds + 999) / 1000);
    }

    /**
     * The state after one more accepted request. A new window starts when there is none yet, when the current one
     * is over, or after an ended lock.
     */
    public PasswordResetDevice afterRequest(Instant now) {
        if (isLocked(now)) {
            throw new IllegalStateException("A locked browser cannot send another password reset request");
        }
        boolean newWindow = windowStartedAt == null || lockedUntil != null
                || !now.isBefore(windowStartedAt.plus(WINDOW));
        int count = newWindow ? 1 : requestCount + 1;
        return new PasswordResetDevice(id, count, newWindow ? now : windowStartedAt,
                count >= MAX_REQUESTS ? now.plus(LOCK_DURATION) : null);
    }
}
