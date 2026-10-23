package vn.ttcs.recruitment.invitation;

import java.time.Instant;

/**
 * Everything one interview email needs, kept as plain values so the outbox can store it (encrypted) and send it later
 * without reading the interview again. kind is INVITATION, UPDATED, CANCELLED or REMOVED. replyUrl is the candidate's
 * personal reply link and stays null in staff emails; reason is the optional cancellation reason.
 *
 * <p>toString leaves out the recipient, the email address, the free texts and every link: the reply link carries the
 * candidate's token, and a payload must never put it or personal data into a log.
 */
public record InterviewMailPayload(String email, String recipientName, String positionTitle, String roundName,
                                   Instant startsAt, Instant endsAt, String mode, String room, String meetingUrl,
                                   String preparationInstructions, String kind, String replyUrl, String reason) {

    @Override
    public String toString() {
        return "InterviewMailPayload[kind=" + kind + ", startsAt=" + startsAt + ", endsAt=" + endsAt
                + ", replyLink=" + (replyUrl != null) + "]";
    }
}
