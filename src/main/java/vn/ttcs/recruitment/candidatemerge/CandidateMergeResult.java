package vn.ttcs.recruitment.candidatemerge;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Task 368 (story S5-01): a merge as committed: its id (for the undo, task 370), the two candidates, when it happened
 * and until when it may be undone, the applications moved to the target and the candidates re-pointed to it. Ids and
 * times only.
 */
public record CandidateMergeResult(UUID mergeId, UUID targetCandidateId, UUID sourceCandidateId, Instant mergedAt,
                                   Instant undoDeadline, List<UUID> movedApplicationIds,
                                   List<UUID> redirectedCandidateIds) { }
