package vn.ttcs.recruitment.candidatemerge;

import vn.ttcs.recruitment.application.ApplicationStage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Task 368 (story S5-01, "Hiển thị so sánh cạnh nhau trước khi gộp"): the two candidates side by side, as the reader
 * may read them: the target that stays and the source that would be merged into it. Each side lists the distinct
 * effective names, emails and phones of its readable applications and the applications themselves, newest first.
 * matchedBy says what the two sides share (effective email, phone in its national form), sharedRequisitionIds the
 * requisitions with an application on both sides (after a merge the target has two applications there).
 * <p>
 * mergeable is false when a merge by this caller would be refused now; blockers says why. The offer and talent pool
 * blockers are only computed for a caller who may write every application of both candidates (otherwise only
 * NOT_WRITABLE is shown), so the preview never tells a recruiter about an offer outside its scope. The answer is a
 * snapshot: the merge checks everything again under its locks.
 */
public record CandidateMergePreview(Side target, Side source, List<DuplicateMatch> matchedBy,
                                    List<UUID> sharedRequisitionIds, boolean mergeable, List<Blocker> blockers) {

    public enum Blocker {
        // One of the candidates was merged into another one already (409 CANDIDATE_ALREADY_MERGED).
        TARGET_MERGED,
        SOURCE_MERGED,
        // The caller lacks CANDIDATES write, or the scope over one of the applications of either candidate (403).
        NOT_WRITABLE,
        // The source holds an active offer (409 CANDIDATE_MERGE_ACTIVE_OFFER) or an active talent pool entry (409
        // CANDIDATE_MERGE_TALENT_POOL).
        SOURCE_ACTIVE_OFFER,
        SOURCE_IN_TALENT_POOL
    }

    public record Side(UUID candidateId, UUID mergedInto, Instant mergedAt, List<String> fullNames,
                       List<String> emails, List<String> phones, List<Application> applications) {
        @Override public String toString() { return "Side[" + candidateId + ", personal data redacted]"; }
    }

    public record Application(UUID applicationId, UUID requisitionId, String postingTitle, String departmentName,
                              String fullName, String email, String phone, ApplicationStage stage, String stageName,
                              Instant receivedAt, boolean sameRequisition) {
        @Override public String toString() { return "Application[" + applicationId + ", personal data redacted]"; }
    }

    static CandidateMergePreview of(CandidateMergeRepository.Candidate target,
                                    List<CandidateMergeRepository.PreviewApplication> targetApplications,
                                    CandidateMergeRepository.Candidate source,
                                    List<CandidateMergeRepository.PreviewApplication> sourceApplications,
                                    List<Blocker> blockers) {
        Set<UUID> shared = new LinkedHashSet<>(targetApplications.stream()
                .map(CandidateMergeRepository.PreviewApplication::requisitionId).toList());
        shared.retainAll(new HashSet<>(sourceApplications.stream()
                .map(CandidateMergeRepository.PreviewApplication::requisitionId).toList()));
        List<DuplicateMatch> matched = new ArrayList<>(2);
        if (overlaps(targetApplications, sourceApplications, true)) matched.add(DuplicateMatch.EMAIL);
        if (overlaps(targetApplications, sourceApplications, false)) matched.add(DuplicateMatch.PHONE);
        return new CandidateMergePreview(side(target, targetApplications, shared), side(source, sourceApplications, shared),
                List.copyOf(matched), List.copyOf(shared), blockers.isEmpty(), List.copyOf(blockers));
    }

    private static boolean overlaps(List<CandidateMergeRepository.PreviewApplication> left,
                                    List<CandidateMergeRepository.PreviewApplication> right, boolean email) {
        Set<String> keys = new HashSet<>(left.stream().map(application -> email ? application.normalizedEmail()
                : application.nationalPhone()).toList());
        return right.stream().anyMatch(application -> keys.contains(email ? application.normalizedEmail()
                : application.nationalPhone()));
    }

    private static Side side(CandidateMergeRepository.Candidate candidate,
                             List<CandidateMergeRepository.PreviewApplication> applications, Set<UUID> shared) {
        return new Side(candidate.id(), candidate.mergedInto(), candidate.mergedAt(),
                distinct(applications.stream().map(CandidateMergeRepository.PreviewApplication::fullName).toList()),
                distinct(applications.stream().map(CandidateMergeRepository.PreviewApplication::email).toList()),
                distinct(applications.stream().map(CandidateMergeRepository.PreviewApplication::phone).toList()),
                applications.stream().map(application -> new Application(application.applicationId(),
                        application.requisitionId(), application.postingTitle(), application.departmentName(),
                        application.fullName(), application.email(), application.phone(), application.stage(),
                        application.stage().displayName(), application.receivedAt(),
                        shared.contains(application.requisitionId()))).toList());
    }

    private static List<String> distinct(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(values));
    }
}
