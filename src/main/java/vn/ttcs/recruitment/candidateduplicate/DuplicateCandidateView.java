package vn.ttcs.recruitment.candidateduplicate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import vn.ttcs.recruitment.cvsuggestion.ContactValues;

public record DuplicateCandidateView(UUID applicationId, long contactRevision, DetectionStatus detectionStatus,
        Instant indexedAt, List<Item> items, int page, int size, boolean hasMore, String restrictedWarning) {
    public enum DetectionStatus { PENDING, AVAILABLE }
    public enum Match { EMAIL, PHONE }
    public record Item(UUID applicationId, UUID requisitionId, String postingTitle, ContactValues contact,
            long contactRevision, List<Match> matchedBy, Instant detectedAt) {
        public Item { matchedBy = List.copyOf(matchedBy); }
        @Override public String toString() { return "DuplicateCandidateItem[personal data redacted]"; }
    }
    public DuplicateCandidateView { items = List.copyOf(items); }
    @Override public String toString() { return "DuplicateCandidateView[personal data redacted]"; }
}
