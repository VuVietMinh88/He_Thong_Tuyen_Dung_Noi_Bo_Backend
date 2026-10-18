package vn.ttcs.recruitment.offerdelivery.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OfferRequestQuotaTest {
    @Test void numericAddressesBecomePurposeBoundHashesAndIpv6UsesOneBucketPerSubnet() {
        String ipv4 = OfferRequestQuota.bucketKey("192.0.2.7");
        assertThat(ipv4).matches("[0-9a-f]{64}").doesNotContain("192.0.2.7");
        assertThat(OfferRequestQuota.bucketKey("::ffff:192.0.2.7")).isEqualTo(ipv4);
        assertThat(OfferRequestQuota.bucketKey("192.0.2.8")).isNotEqualTo(ipv4);
        assertThat(OfferRequestQuota.bucketKey("2001:db8:0:1::1"))
                .isEqualTo(OfferRequestQuota.bucketKey("2001:0db8:0000:0001:ffff:ffff:ffff:ffff"))
                .isNotEqualTo(OfferRequestQuota.bucketKey("2001:db8:0:2::1"));
    }

    @ParameterizedTest @ValueSource(strings = {"", "localhost", "face", "127.1", "012.0.0.1", "256.0.0.1",
            "192.0.2.7:80", "[::1]", "fe80::1%eth0", "https://example.test", "2001:db8:::1"})
    void invalidInputCannotBecomeADnsLookupOrQuotaBypass(String address) {
        assertThatThrownBy(() -> OfferRequestQuota.bucketKey(address))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
