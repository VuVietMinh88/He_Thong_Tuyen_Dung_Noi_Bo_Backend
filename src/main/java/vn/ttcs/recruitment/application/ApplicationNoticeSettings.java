package vn.ttcs.recruitment.application;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Set;
import java.util.UUID;

/**
 * Task 346 (story S4-06): settings of the emails sent after a withdrawal. The candidate profile page
 * (app.candidate-profile.page-url, CANDIDATE_PROFILE_PAGE_URL) is validated like the tracking page of task 329: fixed
 * HTTPS, HTTP only on loopback, no credentials, query or fragment, so a mail link can only lead to the configured
 * frontend; the profile id is appended as one path segment and the page asks for a login. The recruiter delay
 * (app.application-notice.recruiter-delay-seconds, 0..3600, default 900) collects the withdrawals of one requisition
 * into one mail. From, the support address and the Message-ID domain come from app.mail.from, already validated by
 * ApplicationConfirmationSettings, so that class is not changed.
 */
@Component
class ApplicationNoticeSettings {
    static final long MAX_RECRUITER_DELAY_SECONDS = 3600;

    private final String profilePage;
    private final long recruiterDelaySeconds;
    private final String from;
    private final String supportAddress;
    private final String domain;

    ApplicationNoticeSettings(@Value("${app.candidate-profile.page-url}") URI profilePage,
            @Value("${app.application-notice.recruiter-delay-seconds:900}") long recruiterDelaySeconds,
            ApplicationConfirmationSettings confirmation) {
        boolean localHttp = "http".equals(profilePage.getScheme()) && Set.of("localhost", "127.0.0.1", "[::1]")
                .contains(profilePage.getHost() == null ? "" : profilePage.getHost());
        if (profilePage.getHost() == null || profilePage.getRawUserInfo() != null || profilePage.getRawQuery() != null
                || profilePage.getRawFragment() != null || !("https".equals(profilePage.getScheme()) || localHttp)) {
            throw new IllegalArgumentException("CANDIDATE_PROFILE_PAGE_URL must be fixed HTTPS (HTTP only on "
                    + "loopback), without credentials, query or fragment");
        }
        if (recruiterDelaySeconds < 0 || recruiterDelaySeconds > MAX_RECRUITER_DELAY_SECONDS) {
            throw new IllegalArgumentException("Application notice recruiter delay must be 0..3600 seconds");
        }
        String page = profilePage.toASCIIString();
        while (page.endsWith("/")) page = page.substring(0, page.length() - 1);
        this.profilePage = page;
        this.recruiterDelaySeconds = recruiterDelaySeconds;
        this.from = confirmation.from();
        try {
            // One mailbox: ApplicationConfirmationSettings has refused anything else at startup.
            String address = InternetAddress.parse(from, true)[0].getAddress();
            this.supportAddress = address;
            this.domain = address.substring(address.lastIndexOf('@') + 1);
        } catch (AddressException exception) {
            throw new IllegalArgumentException("MAIL_FROM must contain one valid mailbox");
        }
    }

    // The staff page of one candidate profile (task 361): the configured page and the id as its last path segment.
    String profileUrl(UUID candidateId) { return profilePage + "/" + candidateId; }

    long recruiterDelaySeconds() { return recruiterDelaySeconds; }

    // app.mail.from as configured, for the From header.
    String from() { return from; }

    // The bare address of app.mail.from, named in the candidate mail as the place to ask for help.
    String supportAddress() { return supportAddress; }

    // Unique per outbox row; the domain is app.mail.from's, like the confirmation's Message-ID.
    String messageId(UUID outboxId) { return "<application-notice." + outboxId + "@" + domain + ">"; }

    @Override public String toString() { return "ApplicationNoticeSettings[" + profilePage + "]"; }
}
