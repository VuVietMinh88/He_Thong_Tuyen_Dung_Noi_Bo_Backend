package vn.ttcs.recruitment.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import vn.ttcs.recruitment.common.ApiException;

import static org.assertj.core.api.Assertions.*;

class ApplicationClientIpResolverTest {
    private ApplicationClientIpResolver resolver(String trusted) { return new ApplicationClientIpResolver(trusted, "NONE"); }
    private MockHttpServletRequest request(String peer, String... forwarded) {
        var request = new MockHttpServletRequest(); request.setRemoteAddr(peer);
        for (String value : forwarded) request.addHeader("X-Forwarded-For", value);
        request.addHeader("Forwarded", "for=198.51.100.200");
        request.addHeader("X-Real-IP", "198.51.100.201");
        return request;
    }

    @Test void directRequestsIgnoreAllForwardingHeadersEvenMalformedOnes() {
        assertThat(resolver("").resolve(request("192.0.2.1", "evil.invalid", ""))).isEqualTo("192.0.2.1");
        assertThat(resolver("10.0.0.1").resolve(request("192.0.2.1", "198.51.100.7"))).isEqualTo("192.0.2.1");
    }
    @Test void trustsOnlyTheRightmostUntrustedHopNotAnInjectedLeftmostIp() {
        var resolver = resolver("10.0.0.0/24,2001:db8:abcd::/48");
        assertThat(resolver.resolve(request("10.0.0.2", "198.51.100.99, 192.0.2.7, 10.0.0.3"))).isEqualTo("192.0.2.7");
        assertThat(resolver.resolve(request("2001:db8:abcd::2", "192.0.2.7", "10.0.0.3"))).isEqualTo("192.0.2.7");
        assertThat(resolver.resolve(request("10.0.0.2", "10.0.0.3"))).isEqualTo("10.0.0.3");
    }
    @Test void cidrBoundariesAndFamiliesAreRespected() {
        var resolver = resolver("10.0.0.0/25");
        assertThat(resolver.resolve(request("10.0.0.127", "192.0.2.8"))).isEqualTo("192.0.2.8");
        assertThat(resolver.resolve(request("10.0.0.128", "192.0.2.8"))).isEqualTo("10.0.0.128");
        assertThat(resolver.resolve(request("2001:db8::1", "192.0.2.8"))).isEqualTo("2001:db8:0:0:0:0:0:1");
    }
    @Test void normalizesIpv6AndIpv4MappedIpv6() {
        assertThat(resolver("").resolve(request("2001:DB8:0:0::1")))
                .isEqualTo(resolver("").resolve(request("2001:db8::1")));
        assertThat(resolver("").resolve(request("::ffff:192.0.2.8"))).isEqualTo("192.0.2.8");
        assertThat(resolver("192.0.2.8").resolve(request("::ffff:c000:208", "198.51.100.2"))).isEqualTo("198.51.100.2");
    }
    @ParameterizedTest @ValueSource(strings = {"", "unknown", "localhost", "192.0.2.8:1234", "[2001:db8::1]",
            "fe80::1%eth0", "999.1.1.1", "012.0.0.1", "127.1", "0x7f000001", "2001:db8:::1",
            "192.0.2.8,", ",192.0.2.8", "::ffff:012.0.0.1"})
    void invalidForwardedAddressesNeverResolveDnsOrDiscloseInput(String value) {
        assertThatThrownBy(() -> resolver("10.0.0.1").resolve(request("10.0.0.1", value)))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getCode()).isEqualTo("APPLICATION_CLIENT_IP_INVALID");
                    assertThat(error.getCause()).isNull(); assertThat(error.getFieldErrors()).isEmpty();
                });
    }
    @Test void missingHeaderAndExcessiveHeadersFailClosedForTrustedPeers() {
        var resolver = resolver("10.0.0.1");
        for (var request : new MockHttpServletRequest[] { request("10.0.0.1"),
                request("10.0.0.1", "192.0.2.1,".repeat(16) + "192.0.2.2"), request("10.0.0.1", "a".repeat(2049)),
                request("unresolvable.invalid", "192.0.2.1") }) {
            assertThatThrownBy(() -> resolver.resolve(request)).isInstanceOf(ApiException.class);
        }
    }
    @ParameterizedTest @ValueSource(strings = {"localhost", "0.0.0.0/0", "::/0", "10.0.0.1/33", "::1/129",
            "10.0.0.1/-1", "10.0.0.1/abc", "10.0.0.1/1/2", "10.0.0.1,", "10.0.0.1/+8"})
    void invalidTrustConfigurationStopsStartup(String value) {
        assertThatThrownBy(() -> resolver(value)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsUnboundedConfigurationAndFrameworkPeerRewriting() {
        assertThatThrownBy(() -> resolver("a".repeat(4097))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver("10.0.0.1,".repeat(64)+"10.0.0.2")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ApplicationClientIpResolver("", "NATIVE")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ApplicationRateLimitException(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
