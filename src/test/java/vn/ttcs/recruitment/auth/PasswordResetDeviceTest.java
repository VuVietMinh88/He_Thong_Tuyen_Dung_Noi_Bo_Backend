package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import vn.ttcs.recruitment.auth.passwordreset.PasswordResetDevice;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordResetDeviceTest {
    private static final UUID ID = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");
    private static final Instant START = Instant.parse("2026-10-16T01:00:00Z");

    @Test
    void theLimitIsFiveRequestsInFifteenMinutesThenAFifteenMinuteLock() {
        assertThat(PasswordResetDevice.MAX_REQUESTS).isEqualTo(5);
        assertThat(PasswordResetDevice.WINDOW).isEqualTo(Duration.ofMinutes(15));
        assertThat(PasswordResetDevice.LOCK_DURATION).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void theFirstRequestStartsAWindowAndTheFifthLocksUntilFifteenMinutesAfterIt() {
        var device = fresh().afterRequest(START);
        assertThat(device).isEqualTo(new PasswordResetDevice(ID, 1, START, null));
        for (int count = 2; count <= 4; count++) {
            device = device.afterRequest(START.plusSeconds(60L * count));
            assertThat(device).isEqualTo(new PasswordResetDevice(ID, count, START, null));
            assertThat(device.isLocked(START.plusSeconds(60L * count))).isFalse();
        }
        Instant fifth = START.plusSeconds(14 * 60 + 59);
        device = device.afterRequest(fifth);
        assertThat(device).isEqualTo(new PasswordResetDevice(ID, 5, START, fifth.plusSeconds(900)));
        assertThat(device.isLocked(fifth)).isTrue();
        assertThat(device.isLocked(fifth.plusSeconds(900).minusNanos(1))).isTrue();
        assertThat(device.isLocked(fifth.plusSeconds(900))).isFalse();
    }

    @Test
    void aLockedBrowserIsNeverCountedSoItsLockIsNeverExtended() {
        var locked = new PasswordResetDevice(ID, 5, START, START.plusSeconds(900));
        assertThatThrownBy(() -> locked.afterRequest(START.plusSeconds(899)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anEndedLockStartsANewWindow() {
        var locked = new PasswordResetDevice(ID, 5, START, START.plusSeconds(900));
        assertThat(locked.afterRequest(START.plusSeconds(900)))
                .isEqualTo(new PasswordResetDevice(ID, 1, START.plusSeconds(900), null));
        assertThat(locked.afterRequest(START.plus(Duration.ofDays(2))))
                .isEqualTo(new PasswordResetDevice(ID, 1, START.plus(Duration.ofDays(2)), null));
    }

    @Test
    void aWindowEndsFifteenMinutesAfterItsFirstRequest() {
        var four = new PasswordResetDevice(ID, 4, START, null);
        Instant lastMoment = START.plusSeconds(900).minusMillis(1);
        assertThat(four.afterRequest(lastMoment))
                .isEqualTo(new PasswordResetDevice(ID, 5, START, lastMoment.plusSeconds(900)));
        assertThat(four.afterRequest(START.plusSeconds(900)))
                .isEqualTo(new PasswordResetDevice(ID, 1, START.plusSeconds(900), null));
        assertThat(new PasswordResetDevice(ID, 2, START, null).afterRequest(START.plusSeconds(3600)))
                .isEqualTo(new PasswordResetDevice(ID, 1, START.plusSeconds(3600), null));
    }

    // The window is fixed, not sliding (docs/api/password-reset.md): at the edge of two windows nine requests are
    // accepted within fifteen minutes, eight of them within one second, before the browser is locked.
    @Test
    void aFixedWindowAcceptsUpToNineRequestsAcrossItsEdge() {
        var device = fresh().afterRequest(START);
        int accepted = 1;
        Instant justBeforeTheEdge = START.plusSeconds(899);
        for (int request = 0; request < 3; request++) {
            device = device.afterRequest(justBeforeTheEdge);
            accepted++;
        }
        Instant edge = START.plusSeconds(900);
        while (!device.isLocked(edge)) {
            device = device.afterRequest(edge);
            accepted++;
        }
        assertThat(accepted).isEqualTo(9);
        assertThat(device).isEqualTo(new PasswordResetDevice(ID, 5, edge, edge.plusSeconds(900)));
    }

    @Test
    void retryAfterIsRoundedUpToWholeSecondsAndIsAtLeastOne() {
        var locked = new PasswordResetDevice(ID, 5, START, START.plusSeconds(900));
        assertThat(locked.retryAfterSeconds(START)).isEqualTo(900);
        assertThat(locked.retryAfterSeconds(START.plusMillis(1))).isEqualTo(900);
        assertThat(locked.retryAfterSeconds(START.plusMillis(1000))).isEqualTo(899);
        assertThat(locked.retryAfterSeconds(START.plusSeconds(899))).isEqualTo(1);
        assertThat(locked.retryAfterSeconds(START.plusSeconds(900).minusMillis(1))).isEqualTo(1);
        assertThat(locked.retryAfterSeconds(START.plusSeconds(900).minusNanos(1))).isEqualTo(1);
    }

    @Test
    void theBrowserIdMustBeCanonicalUuidText() {
        assertThat(BrowserDeviceId.parse("0f8fad5b-d9cb-469f-a165-70867728950e")).contains(ID);
        assertThat(BrowserDeviceId.parse("0F8FAD5B-D9CB-469F-A165-70867728950E")).contains(ID);
        assertThat(BrowserDeviceId.HEADER).isEqualTo("X-Device-Id");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"abc", "1-1-1-1-1", "0f8fad5bd9cb469fa16570867728950e",
            "0f8fad5b-d9cb-469f-a165-70867728950ex", " 0f8fad5b-d9cb-469f-a165-70867728950e",
            "{0f8fad5b-d9cb-469f-a165-70867728950e}", "0f8fad5b-d9cb-469f-a165-70867728950g",
            "0f8fad5b-d9cb-469f-a165-70867728950e\n"})
    void anythingElseIsNotABrowserId(String header) {
        assertThat(BrowserDeviceId.parse(header)).isEmpty();
    }

    private static PasswordResetDevice fresh() {
        return new PasswordResetDevice(ID, 0, null, null);
    }
}
