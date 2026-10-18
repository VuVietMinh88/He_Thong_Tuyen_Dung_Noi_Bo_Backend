package vn.ttcs.recruitment.offer;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OfferNegotiationView(UUID id, UUID offerId, String reason, String comment,
                                   UUID actorId, String actorName, Instant occurredAt) {
    public record Page(List<OfferNegotiationView> items, int page, int size, long totalElements, long totalPages) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Suggestion(UUID offerId, UUID applicationId, UUID candidateId,
                             boolean suggested, String reason, String declineReason,
                             String declineComment, String declineSource) { }

    @Override
    public String toString() {
        return "OfferNegotiation[id=" + id + ", offerId=" + offerId + ", note redacted]";
    }
}
