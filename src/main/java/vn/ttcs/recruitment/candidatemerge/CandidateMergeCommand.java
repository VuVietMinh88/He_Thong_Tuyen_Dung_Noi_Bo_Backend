package vn.ttcs.recruitment.candidatemerge;

import java.util.UUID;

/** Trusted internal storage request, not an HTTP request body or an authorization decision. */
public record CandidateMergeCommand(UUID actorId, UUID requestKey, UUID leftGroupId, UUID rightGroupId,
        UUID resultGroupId, UUID representativeApplicationId, String expectedFingerprint) {
    public CandidateMergeCommand {
        if (actorId == null || requestKey == null || leftGroupId == null || rightGroupId == null
                || resultGroupId == null || representativeApplicationId == null || leftGroupId.equals(rightGroupId)
                || (!resultGroupId.equals(leftGroupId) && !resultGroupId.equals(rightGroupId))
                || expectedFingerprint == null || !expectedFingerprint.matches("[0-9a-f]{64}"))
            throw new MergeAuditException(MergeAuditException.Reason.INVALID_REQUEST);
        // PostgreSQL UUID ordering is unsigned, unlike UUID.compareTo(). Canonical text has the same order as SQL.
        if (leftGroupId.toString().compareTo(rightGroupId.toString()) > 0) {
            UUID swap = leftGroupId; leftGroupId = rightGroupId; rightGroupId = swap;
        }
    }

    byte[] payloadHash() {
        return CandidateMergeSnapshot.hash("candidate-merge-command-v1\n" + actorId + '\n' + leftGroupId + '\n'
                + rightGroupId + '\n' + resultGroupId + '\n' + representativeApplicationId + '\n' + expectedFingerprint);
    }

    @Override public String toString() { return "CandidateMergeCommand[internal data redacted]"; }
}
