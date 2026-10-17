package vn.ttcs.recruitment.candidatemerge;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import vn.ttcs.recruitment.cvsuggestion.ContactValues;

public final class CandidateMergeApi {
    private CandidateMergeApi() { }
    public record PreviewRequest(@NotNull UUID leftApplicationId,@NotNull UUID rightApplicationId) { }
    public record MergeRequest(@NotNull UUID requestKey,@NotNull UUID leftGroupId,@NotNull UUID rightGroupId,
            @NotNull UUID resultGroupId,@NotNull UUID representativeApplicationId,
            @NotNull @Pattern(regexp="[0-9a-f]{64}") String expectedFingerprint) { }
    public record UndoRequest(@NotNull UUID requestKey) { }
    public record Member(UUID applicationId,UUID groupId,UUID requisitionId,String postingTitle,
            ContactValues contact,long contactRevision) {
        @Override public String toString() { return "MergeMember[personal data redacted]"; }
    }
    public record Preview(List<CandidateMergeSnapshot.Group> groups,List<Member> members,
            List<Match> matches,boolean matchesTruncated,String fingerprint) {
        public Preview { groups=List.copyOf(groups);members=List.copyOf(members);matches=List.copyOf(matches); }
        @Override public String toString() { return "MergePreview[personal data redacted]"; }
    }
    public record Match(UUID leftApplicationId,UUID rightApplicationId,boolean email,boolean phone) { }
    public record Result(UUID id,UUID resultGroupId,UUID representativeApplicationId,
            Instant recordedAt,Instant undoDeadline,boolean undone) { }
    public record UndoResult(UUID id,UUID mergeOperationId,Instant recordedAt) { }
}
