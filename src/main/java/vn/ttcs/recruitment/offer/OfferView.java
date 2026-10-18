package vn.ttcs.recruitment.offer;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record OfferView(UUID id, UUID applicationId, UUID candidateId, UUID positionId,
    String candidateName, String email, String positionTitle, long salary, long allowance,
    LocalDate startDate, Instant responseDeadline, String explanation, boolean outsideBand,
    Long bandMin, Long bandMax, String status, long version, int approvalRound, UUID createdBy,
    UUID submittedBy, UUID hrApproverId, UUID higherApproverId, Instant approvedAt, Instant createdAt,
    Instant updatedAt, String deliveryStatus, Instant sentAt, Instant respondedAt, String responseNote,
    String declineReason, UUID previousOfferId, int revisionNumber, UUID replacedByOfferId) {
    public record Page(List<OfferView> items, int page, int size, long totalElements, long totalPages) {
    }

    public record History(List<Event> items, int page, int size, long totalElements, long totalPages) {
    }

    public record Event(UUID id, int approvalRound, String eventType, UUID actorId, String actorName,
        Instant occurredAt, String comment) {
    }

    public record Approver(UUID id, String fullName) {
    }

    public record Preview(String subject, String plainText, String html) {
    }

    public OfferView withoutBand() {
        return new OfferView(id, applicationId, candidateId, positionId, candidateName, email, positionTitle, salary, allowance,
            startDate, responseDeadline, explanation, outsideBand, null, null, status, version, approvalRound, createdBy,
            submittedBy, hrApproverId, higherApproverId, approvedAt, createdAt, updatedAt, deliveryStatus, sentAt, respondedAt, responseNote,
            declineReason, previousOfferId, revisionNumber, replacedByOfferId);
    }
    @Override public String toString() {
        return "OfferView[id=" + id + ", status=" + status + ", terms redacted]";
    }
}
