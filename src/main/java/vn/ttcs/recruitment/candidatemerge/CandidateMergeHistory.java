package vn.ttcs.recruitment.candidatemerge;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Task 370 (story S5-01): the merges of one candidate, as their target or their source, newest first. undoable says
 * the merge is not undone and its deadline has not passed now; the undo itself still checks the changes made since
 * (409 CANDIDATE_MERGE_CHANGED). truncated: there are more merges than listed. Ids, staff names, times and the staff's
 * reason only; no application data, so reading it is not logged as a profile read.
 */
public record CandidateMergeHistory(UUID candidateId, List<Item> merges, boolean truncated) {

    // What the candidate was in the merge: the profile that stayed, or the one merged into it.
    public enum Role { TARGET, SOURCE }

    public record Staff(UUID id, String fullName) {
        @Override public String toString() { return "Staff[" + id + ", name redacted]"; }
    }

    public record Item(UUID mergeId, Role role, UUID targetCandidateId, UUID sourceCandidateId, String reason,
                       Staff mergedBy, Instant mergedAt, Instant undoDeadline, Staff undoneBy, Instant undoneAt,
                       int movedApplications, boolean undoable) {
        @Override public String toString() { return "Item[" + mergeId + ", reason redacted]"; }
    }

    static CandidateMergeHistory of(UUID candidateId, List<CandidateMergeRecords.CandidateMerge> merges, int limit,
                                    Map<UUID, String> names, Map<UUID, Integer> moved, Instant now) {
        List<Item> items = merges.stream().limit(limit).map(merge -> new Item(merge.id(),
                merge.targetCandidateId().equals(candidateId) ? Role.TARGET : Role.SOURCE, merge.targetCandidateId(),
                merge.sourceCandidateId(), merge.reason(), staff(merge.mergedBy(), names), merge.mergedAt(),
                merge.undoDeadline(), staff(merge.undoneBy(), names), merge.undoneAt(),
                moved.getOrDefault(merge.id(), 0), merge.undoableAt(now))).toList();
        return new CandidateMergeHistory(candidateId, items, merges.size() > limit);
    }

    private static Staff staff(UUID id, Map<UUID, String> names) {
        return id == null ? null : new Staff(id, names.get(id));
    }
}
