package vn.ttcs.recruitment.messaging;

import vn.ttcs.recruitment.application.ApplicationStage;

import java.util.Objects;

/**
 * Task 538: whether a queued template-only email may still go out, decided from current data just before SMTP. A
 * rejection letter needs the application to be REJECTED still; a thank-you needs its interview and an application
 * still in an active stage (NEW, SCREENING, INTERVIEW, OFFER): after a final decision (REJECTED, HIRED, ONBOARDED) or a
 * withdrawal it is not sent. The address must still be the one the email was decided for: an application whose email
 * was corrected is not mailed at an address nobody chose for this email.
 */
final class CandidateMailPolicy {
    private CandidateMailPolicy() {
    }

    /** Null when the email may be sent, otherwise OBSOLETE or RECIPIENT_UNAVAILABLE. */
    static String unavailableReason(CandidateMailOutboxRepository.Content content) {
        boolean current = switch (content.type()) {
            case REJECTION -> "REJECTED".equals(content.stage());
            case INTERVIEW_THANKS -> content.interviewId() != null && content.interviewStartsAt() != null
                    && content.stage() != null && ApplicationStage.valueOf(content.stage()).active();
            // These types have their own outboxes and never reach this queue.
            case APPLICATION_RECEIPT, INTERVIEW_INVITATION, OFFER -> false;
        };
        if (!current) return "OBSOLETE";
        return Objects.equals(content.recipient(), content.currentEmail()) ? null : "RECIPIENT_UNAVAILABLE";
    }
}
