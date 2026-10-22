package vn.ttcs.recruitment.cvsuggestion;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ConfirmedContactView(UUID applicationId,ContactValues submitted,Confirmation confirmed,
        ContactValues effective,long revision,String currentCvHash,boolean needsReview) {
    public record Confirmation(ContactValues contact,long revision,String cvHash,ContactSource nameSource,
            ContactSource emailSource,ContactSource phoneSource,UUID extractionId,UUID confirmedBy,
            String confirmedByName,Instant confirmedAt) {
        @Override public String toString() { return "Confirmation[revision="+revision+", personal data redacted]"; }
    }
    public record HistoryItem(long revision,ContactValues beforeContact,Confirmation afterContact,
            UUID actorId,String actorName,Instant confirmedAt) {
        @Override public String toString() { return "HistoryItem[revision="+revision+", personal data redacted]"; }
    }
    public record HistoryPage(List<HistoryItem> items,int page,int size,long totalElements) {
        public HistoryPage { items=List.copyOf(items); }
        @Override public String toString() { return "HistoryPage[items="+items.size()+"]"; }
    }
    @Override public String toString() { return "ConfirmedContactView[applicationId="+applicationId+", revision="+revision+"]"; }
}
