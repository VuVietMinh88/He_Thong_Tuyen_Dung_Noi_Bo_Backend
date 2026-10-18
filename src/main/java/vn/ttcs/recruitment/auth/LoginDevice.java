package vn.ttcs.recruitment.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** State of consecutive rejected logins for one browser, shared across emails. */
public record LoginDevice(UUID id, int failedAttempts, Instant lockedUntil) {
    public static final int MAX_FAILED_ATTEMPTS = 5;
    public static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    public boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    public long retryAfterSeconds(Instant now) {
        long milliseconds = Duration.between(now, lockedUntil).toMillis();
        return Math.max(1, (milliseconds + 999) / 1000);
    }

    public LoginDevice afterFailure(Instant now) {
        int attempts = lockedUntil != null ? 1 : failedAttempts + 1;
        return new LoginDevice(id, attempts,
                attempts >= MAX_FAILED_ATTEMPTS ? now.plus(LOCK_DURATION) : null);
    }
}
