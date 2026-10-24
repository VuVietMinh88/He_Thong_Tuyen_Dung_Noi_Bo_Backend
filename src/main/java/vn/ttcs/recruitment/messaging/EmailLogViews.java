package vn.ttcs.recruitment.messaging;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Task 539: the email log as the API returns it. */
public final class EmailLogViews {
    private EmailLogViews() {
    }

    /**
     * One candidate email: its template type, the time it was decided and sent, its result and safe failure code, how
     * many attempts it had and whether the caller may retry it now (FAILED only).
     */
    public record Message(UUID id, String type, String typeName, String variant, UUID applicationId,
                          String candidateName, String positionTitle, UUID interviewId, UUID offerId, String trigger,
                          String status, String recipientEmail, String lastFailure, int attemptCount, int retryCount,
                          Instant createdAt, Instant updatedAt, Instant sentAt, String createdByName,
                          Instant lastRetriedAt, String lastRetriedByName, boolean retryable) {
        @Override public String toString() { return "EmailLogMessage[" + id + ", " + type + ", " + status + "]"; }
    }

    public record Page(List<Message> items, int page, int size, long totalItems, int totalPages) {
    }

    /** One immutable attempt of the message. */
    public record Attempt(int number, String result, String errorCode, String templateType, Long templateVersion,
                          String recipientEmail, Instant attemptedAt) {
    }

    public record Detail(Message message, List<Attempt> attempts) {
    }
}
