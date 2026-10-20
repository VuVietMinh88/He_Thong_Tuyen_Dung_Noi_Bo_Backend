package vn.ttcs.recruitment.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Task 339: one tracking bucket per IPv4 address and per IPv6 /64.
class TrackingBucketsTest {
    @Test
    void anIpv4AddressIsItsOwnBucket() {
        assertThat(TrackingBuckets.of("192.0.2.7")).isEqualTo("192.0.2.7");
        assertThat(TrackingBuckets.of("192.0.2.8")).isEqualTo("192.0.2.8");
    }

    @Test
    void theAddressesOfOneIpv6SlashSixtyFourShareABucket() {
        assertThat(TrackingBuckets.of("2001:db8:0:1::1")).isEqualTo("2001:db8:0:1:0:0:0:0")
                .isEqualTo(TrackingBuckets.of("2001:db8:0:1::2"))
                .isEqualTo(TrackingBuckets.of("2001:db8:0:1:ffff:ffff:ffff:ffff"));
        assertThat(TrackingBuckets.of("2001:db8:0:2::1")).isEqualTo("2001:db8:0:2:0:0:0:0");
    }

    @Test
    void anIpv4MappedAddressSharesTheIpv4Bucket() {
        assertThat(TrackingBuckets.of("::ffff:192.0.2.7")).isEqualTo("192.0.2.7");
    }

    @ParameterizedTest
    @ValueSource(strings = {"192.0.2.7", "2001:db8:0:1::5", "::1", "::ffff:192.0.2.7", "fe80::1"})
    void everyBucketIsAHostAddressWhoseLowSixtyFourBitsAreZeroForIpv6(String address) throws Exception {
        String bucket = TrackingBuckets.of(address);
        assertThat(bucket).doesNotContain("/", "%");
        InetAddress parsed = InetAddress.getByName(bucket);
        assertThat(parsed.getHostAddress()).isEqualTo(bucket);
        if (parsed instanceof Inet6Address) {
            assertThat(Arrays.copyOfRange(parsed.getAddress(), 8, 16)).containsOnly(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "localhost", "example.test", "192.0.2.7/24", "fe80::1%eth0"})
    void onlyANumericLiteralIsAccepted(String value) {
        assertThatThrownBy(() -> TrackingBuckets.of(value)).isInstanceOf(IllegalArgumentException.class);
    }
}
