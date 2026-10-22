package vn.ttcs.recruitment.candidatemerge;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Task 370 (story S5-01): an undo as committed: the merge, the two candidates, when it was undone, the applications
 * linked to the source again (the ones the merge moved and the ones that joined the target later through an email of
 * the source) and the candidates pointed at the source again. Ids and times only.
 */
public record CandidateMergeUndoResult(UUID mergeId, UUID targetCandidateId, UUID sourceCandidateId, Instant undoneAt,
                                       List<UUID> restoredApplicationIds, List<UUID> restoredCandidateIds) { }
