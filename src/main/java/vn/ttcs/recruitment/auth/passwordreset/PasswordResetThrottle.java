package vn.ttcs.recruitment.auth.passwordreset;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Limits forgot-password requests per browser: {@link PasswordResetDevice#MAX_REQUESTS} accepted requests per
 * {@link PasswordResetDevice#WINDOW}, then a lock. The email of a request is never looked at, so known and unknown
 * emails count the same and the limit reveals nothing about accounts. Independent of the account's own one-minute
 * email cooldown (PasswordResetService).
 */
@Service
public class PasswordResetThrottle {
    private final PasswordResetDeviceRepository devices;
    private final Clock clock;

    public PasswordResetThrottle(PasswordResetDeviceRepository devices, Clock clock) {
        this.devices = devices;
        this.clock = clock;
    }

    /**
     * Counts one request of the browser and returns empty, or returns the whole seconds it must wait when it is
     * locked (nothing is counted then). The count is committed when this returns, before the email is queued.
     */
    @Transactional
    public OptionalLong admit(UUID deviceId) {
        Instant now = clock.instant();
        PasswordResetDevice device = devices.lock(deviceId, now);
        if (device.isLocked(now)) {
            return OptionalLong.of(device.retryAfterSeconds(now));
        }
        devices.save(device.afterRequest(now), now);
        return OptionalLong.empty();
    }
}
