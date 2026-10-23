package vn.ttcs.recruitment.interview;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record InterviewBulkPreview(boolean canCreate, List<Slot> slots) {
    public record Slot(UUID applicationId, Instant startsAt, Instant endsAt, List<InterviewConflict> conflicts) { }
}
