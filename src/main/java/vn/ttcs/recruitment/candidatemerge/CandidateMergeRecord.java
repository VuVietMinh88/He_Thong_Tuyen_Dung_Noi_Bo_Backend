package vn.ttcs.recruitment.candidatemerge;

import java.time.Instant;
import java.util.UUID;

public record CandidateMergeRecord(UUID id, UUID actorId, String actorName, UUID requestKey, String payloadHash,
        CandidateMergeSnapshot before, UUID resultGroupId, UUID representativeApplicationId,
        long leftRevisionAfter, long rightRevisionAfter, Instant recordedAt, Instant undoDeadline) {
    @Override public String toString() { return "CandidateMergeRecord[internal data redacted]"; }
}
