package vn.ttcs.recruitment.application;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 344 (story S4-06): the withdrawal link of the confirmation email is the validated tracking page with the
 * application's own code under "#rut=", the same page as the "#ma=" tracking link of task 329, with no query and no
 * second secret.
 */
class ApplicationWithdrawalLinkTest {
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    // A canonical code: every Base64URL character, ending in one whose two unused bits are zero.
    private static final String CODE = "Ab0-_z9ZyXwVuTsRqPoNmLkJiHgFeDcBa1234567890";

    private static ApplicationConfirmationSettings settings(String page) {
        return new ApplicationConfirmationSettings(URI.create(page), "support@example.test", KEY, "v1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://portal.example.test/tra-cuu",
            "https://portal.example.test/careers/applications/lookup", "http://localhost:5173/tra-cuu",
            "http://127.0.0.1:5173/tra-cuu", "http://[::1]:5173/tra-cuu"})
    void theLinkIsTheTrackingPageWithTheCodeUnderRut(String page) {
        var settings = settings(page);
        String link = settings.withdrawalUrl(CODE);

        assertThat(CODE).matches(TrackingCode.PATTERN);
        assertThat(TrackingCode.hash(CODE)).isPresent();
        assertThat(link).isEqualTo(page + "#rut=" + CODE);
        // The same page as the tracking link: only the fragment differs.
        String tracking = settings.trackingUrl(CODE);
        assertThat(link.substring(0, link.indexOf('#'))).isEqualTo(tracking.substring(0, tracking.indexOf('#')))
                .isEqualTo(page);
        var uri = URI.create(link);
        var trackingUri = URI.create(tracking);
        assertThat(uri.getScheme()).isEqualTo(trackingUri.getScheme());
        assertThat(uri.getRawAuthority()).isEqualTo(trackingUri.getRawAuthority());
        assertThat(uri.getRawPath()).isEqualTo(trackingUri.getRawPath());
        assertThat(uri.getRawUserInfo()).isNull();
        // No query: a code in a query would reach the server's and the proxy's logs and the Referer.
        assertThat(uri.getRawQuery()).isNull();
        assertThat(link).doesNotContain("?");
        // The code is the whole fragment, unchanged (Base64URL needs no escaping), under rut= and never ma=.
        assertThat(uri.getRawFragment()).isEqualTo("rut=" + CODE);
        assertThat(link).doesNotContain("#ma=").containsOnlyOnce("#").containsOnlyOnce(CODE);
        assertThat(trackingUri.getRawFragment()).isEqualTo("ma=" + CODE);
    }
}
