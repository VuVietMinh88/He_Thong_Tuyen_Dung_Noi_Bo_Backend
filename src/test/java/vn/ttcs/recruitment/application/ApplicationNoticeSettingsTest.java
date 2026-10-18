package vn.ttcs.recruitment.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 346 (story S4-06): the settings of the withdrawal emails. The profile page the recruiter mail links to is a
 * fixed HTTPS page (HTTP only on loopback) without credentials, query or fragment, like the tracking page of task 329;
 * the recruiter delay is 0..3600 seconds; From, the support address and the Message-ID domain come from app.mail.from.
 */
class ApplicationNoticeSettingsTest {
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final UUID CANDIDATE = UUID.fromString("0f8e2d1c-3b4a-4c5d-8e9f-a0b1c2d3e4f5");

    private static ApplicationConfirmationSettings confirmation(String from) {
        return new ApplicationConfirmationSettings(URI.create("https://portal.example.test/tra-cuu"), from, KEY, "v1");
    }

    private static ApplicationNoticeSettings settings(String page, long delay) {
        return new ApplicationNoticeSettings(URI.create(page), delay, confirmation("support@internal-hire.example"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://internal-hire.example/candidates", "http://localhost:5173/candidates",
            "http://127.0.0.1:5173/candidates", "http://[::1]:5173/candidates", "https://internal-hire.example"})
    void theProfileLinkIsTheConfiguredPageWithTheIdAsItsLastSegment(String page) {
        String link = settings(page, 900).profileUrl(CANDIDATE);

        assertThat(link).isEqualTo(page + "/" + CANDIDATE);
        var uri = URI.create(link);
        assertThat(uri.getRawQuery()).isNull();
        assertThat(uri.getRawFragment()).isNull();
        assertThat(uri.getRawUserInfo()).isNull();
    }

    // A trailing slash does not double the separator.
    @Test
    void aTrailingSlashIsNotDoubled() {
        assertThat(settings("https://internal-hire.example/candidates/", 900).profileUrl(CANDIDATE))
                .isEqualTo("https://internal-hire.example/candidates/" + CANDIDATE);
    }

    // A link in a mail must only lead to the configured frontend: no plain HTTP off loopback, no other scheme, no
    // credentials, no query or fragment (the id would not be the last segment), no relative page.
    @ParameterizedTest
    @ValueSource(strings = {"http://internal-hire.example/candidates", "ftp://internal-hire.example/candidates",
            "javascript:alert(1)", "https://user:secret@internal-hire.example/candidates",
            "https://internal-hire.example/candidates?id=", "https://internal-hire.example/candidates#profile",
            "/candidates", "http://localhost.example.test/candidates", "http://10.0.0.1/candidates"})
    void anUnsafePageIsRefusedAtStartup(String page) {
        assertThatThrownBy(() -> settings(page, 900)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CANDIDATE_PROFILE_PAGE_URL");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1, 900, 3600})
    void theRecruiterDelayIsZeroToOneHour(long delay) {
        assertThat(settings("https://internal-hire.example/candidates", delay).recruiterDelaySeconds())
                .isEqualTo(delay);
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 3601, Long.MAX_VALUE, Long.MIN_VALUE})
    void aDelayOutsideZeroToOneHourIsRefused(long delay) {
        assertThatThrownBy(() -> settings("https://internal-hire.example/candidates", delay))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("0..3600");
    }

    // The Message-ID is unique per outbox row and uses the domain of app.mail.from, like the confirmation's; From is
    // app.mail.from as configured and the support address is its bare mailbox.
    @Test
    void messageIdFromAndSupportAddressComeFromTheMailFrom() {
        var settings = new ApplicationNoticeSettings(URI.create("https://internal-hire.example/candidates"), 900,
                confirmation("Tuyen dung <support@internal-hire.example>"));
        UUID row = UUID.fromString("11111111-2222-4333-8444-555555555555");

        assertThat(settings.messageId(row))
                .isEqualTo("<application-notice.11111111-2222-4333-8444-555555555555@internal-hire.example>");
        assertThat(settings.messageId(UUID.randomUUID())).isNotEqualTo(settings.messageId(row))
                .matches("<application-notice\\.[0-9a-f-]{36}@internal-hire\\.example>");
        assertThat(settings.from()).isEqualTo("Tuyen dung <support@internal-hire.example>");
        assertThat(settings.supportAddress()).isEqualTo("support@internal-hire.example");
        assertThat(settings.toString()).doesNotContain("support@");
    }
}
