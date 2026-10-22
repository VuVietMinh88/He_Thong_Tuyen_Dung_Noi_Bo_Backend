package vn.ttcs.recruitment.cvsuggestion;

import java.time.Instant;
import java.util.UUID;
import vn.ttcs.recruitment.cvextraction.CvExtractionResult;

public record CvSuggestionView(UUID id, UUID applicationId, String extractorVersion, String status, int attempt,
        Instant createdAt, Instant updatedAt, CvExtractionResult result, String errorCode) {
    @Override public String toString() { return "CvSuggestionView[id=" + id + ", status=" + status + "]"; }
}
