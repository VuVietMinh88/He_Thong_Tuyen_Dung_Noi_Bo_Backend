package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.application.ApplicationStorageService;
import vn.ttcs.recruitment.application.CvValidator;

import java.net.http.HttpResponse;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 339: the tracking bucket behind a trusted proxy, over a real socket. Its own class (like the teammate's
 * ApplicationTrustedProxyQuotaIntegrationTest), because trusting 127.0.0.1 turns every request without
 * X-Forwarded-For into 400 APPLICATION_CLIENT_IP_INVALID. Three attempts per bucket here. Every lookup is well formed
 * with a code nobody has, so it counts and answers 404 until the bucket is full.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=false", "debug=false", "logging.level.root=WARN",
        "app.auth.jwt-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.application-intake.trusted-proxies=127.0.0.1,10.0.0.0/24", "app.application-intake.cleanup-delay-ms=3600000",
        "app.application-tracking.max-attempts=3", "app.application-tracking.cleanup-delay-ms=3600000"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApplicationTrackingTrustedProxyIntegrationTest {
    private static final String EMAIL = "candidate@example.test";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private Environment environment;
    @Autowired private ApplicationStorageService storage;
    @Autowired private CvValidator validator;

    private ApplicationTestSupport applications;

    @BeforeEach
    void fixture() {
        applications = new ApplicationTestSupport(jdbc, json, storage, validator,
                Integer.parseInt(environment.getRequiredProperty("local.server.port")));
        applications.reset();
    }

    // The rightmost address that is not a trusted proxy is the client; a forged leftmost value changes nothing.
    @Test
    void theRightmostUntrustedAddressIsTheBucket() throws Exception {
        for (int i = 1; i <= 3; i++) assertThat(lookUp("198.51.100." + i + ", 192.0.2.7, 10.0.0.2")).isEqualTo(404);
        assertThat(lookUp("198.51.100.99, 192.0.2.7, 10.0.0.2")).isEqualTo(429);
        assertThat(lookUp("192.0.2.8, 10.0.0.2")).isEqualTo(404);
        assertThat(attempts("192.0.2.7")).isEqualTo(3);
        assertThat(attempts("192.0.2.8")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_tracking_ip_attempts", Integer.class)).isEqualTo(4);
    }

    // No usable client address: a neutral 400 before the quota, which neither names the proxy nor echoes the header.
    @Test
    void aMissingOrMalformedForwardedHeaderIsRefusedWithoutAQuotaRow() throws Exception {
        for (String forwarded : new String[] {null, "malicious.invalid", "192.0.2.1,", "192.0.2.1:80"}) {
            var response = send(forwarded);
            assertThat(response.statusCode()).as(forwarded).isEqualTo(400);
            var body = json.readTree(response.body());
            assertThat(body.get("code").asString()).isEqualTo("APPLICATION_CLIENT_IP_INVALID");
            assertThat(body.get("message").asString()).isEqualTo("Không thể xác định địa chỉ truy cập. Vui lòng thử lại sau.");
            assertThat(response.body()).doesNotContain("127.0.0.1", "malicious", "192.0.2.1", "hồ sơ");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_tracking_ip_quotas", Integer.class)).isZero();
    }

    // One IPv6 subscriber holds a /64: its addresses share one bucket, stored as the first address of the /64.
    @Test
    void theAddressesOfOneIpv6SlashSixtyFourShareABucket() throws Exception {
        assertThat(lookUp("2001:db8:0:1::1")).isEqualTo(404);
        assertThat(lookUp("2001:db8:0:1::2")).isEqualTo(404);
        assertThat(lookUp("2001:db8:0:1:abcd::7")).isEqualTo(404);
        assertThat(lookUp("2001:db8:0:1::2")).isEqualTo(429);
        assertThat(lookUp("2001:db8:0:2::1")).isEqualTo(404);
        assertThat(jdbc.queryForList("SELECT host(client_ip) FROM application_tracking_ip_quotas ORDER BY client_ip", String.class))
                .containsExactly("2001:db8:0:1::", "2001:db8:0:2::");
        assertThat(attempts("2001:db8:0:1::")).isEqualTo(3);
    }

    @Test
    void anIpv4MappedAddressAndThePlainAddressShareABucket() throws Exception {
        for (int i = 0; i < 3; i++) assertThat(lookUp("::ffff:192.0.2.7")).isEqualTo(404);
        assertThat(lookUp("192.0.2.7")).isEqualTo(429);
        assertThat(jdbc.queryForList("SELECT host(client_ip) FROM application_tracking_ip_quotas", String.class))
                .containsExactly("192.0.2.7");
    }

    private int lookUp(String forwarded) throws Exception {
        var response = send(forwarded);
        return response.statusCode();
    }

    private HttpResponse<String> send(String forwarded) throws Exception {
        return applications.lookup(applications.unknownCode(), EMAIL,
                forwarded == null ? Map.of() : Map.of("X-Forwarded-For", forwarded));
    }

    private int attempts(String bucket) {
        return jdbc.queryForObject("SELECT count(*) FROM application_tracking_ip_attempts WHERE client_ip = CAST(? AS inet)",
                Integer.class, bucket);
    }
}
