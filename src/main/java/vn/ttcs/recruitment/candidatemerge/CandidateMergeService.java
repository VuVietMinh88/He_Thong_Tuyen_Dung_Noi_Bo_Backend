package vn.ttcs.recruitment.candidatemerge;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.candidate.CandidateAccess;
import vn.ttcs.recruitment.candidate.CandidateAccessKind;
import vn.ttcs.recruitment.candidate.CandidateReader;
import vn.ttcs.recruitment.candidate.CandidateText;
import vn.ttcs.recruitment.candidate.CandidateWriteGuard;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.security.AccessScope;
import vn.ttcs.recruitment.security.PermissionModule;
import vn.ttcs.recruitment.security.PermissionService;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Task 368 (story S5-01, "Hiển thị so sánh cạnh nhau trước khi gộp; gộp giữ lại toàn bộ lịch sử ứng tuyển của cả hai
 * hồ sơ"): the side by side preview of two candidates and the merge of one (the source) into the other (the target).
 * <p>
 * The preview is a candidate read in the order of every candidate endpoint (CandidateAccess): requireReader before any
 * lookup, 404 CANDIDATE_NOT_FOUND for a candidate that is unknown (no row, or neither merged nor linked to an
 * application), then 403 for a side without a readable application, logged as a refused PROFILE read after the read
 * transaction ended. A merged side has no applications of its own, so it is readable when the candidate it was merged
 * into is. Every application shown is logged as a granted PROFILE read of its side.
 * <p>
 * The merge is a candidate write about every application of both candidates: the caller's account and session, then
 * the requisitions and the applications FOR NO KEY UPDATE, then the candidate rows FOR UPDATE (lock order in
 * CandidateMergeRepository), with CANDIDATES read and write checked again after the lock waits. The caller must be
 * able to write every application of both candidates (403 otherwise), because the merge changes whose profile each of
 * them is. Refused with 409 when either candidate was merged already, or when the source holds an active offer or an
 * active talent pool entry: both name the source for good (the offer's candidate never changes), so its applications
 * cannot move away from it while they are active. Otherwise the source's links move to the target, the source and the
 * candidates merged into it point at the target, and the trail of task 369 records what an undo needs, with a deadline
 * of 24 hours (UNDO_WINDOW, the injected Clock). Every application keeps its rows, stages and history: only the link
 * to the profile moves.
 */
@Service
public class CandidateMergeService {
    static final Duration UNDO_WINDOW = Duration.ofHours(24);
    private final CandidateAccess access;
    private final CandidateWriteGuard guard;
    private final CandidateMergeRepository merges;
    private final CandidateMergeRecords records;
    private final PermissionService permissions;
    private final Clock clock;
    private final TransactionTemplate reads;
    private final TransactionTemplate writes;

    CandidateMergeService(CandidateAccess access, CandidateWriteGuard guard, CandidateMergeRepository merges,
                          CandidateMergeRecords records, PermissionService permissions, Clock clock,
                          PlatformTransactionManager transactions) {
        this.access = access;
        this.guard = guard;
        this.merges = merges;
        this.records = records;
        this.permissions = permissions;
        this.clock = clock;
        reads = new TransactionTemplate(transactions);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        writes = new TransactionTemplate(transactions);
        writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        writes.setTimeout(5);
    }

    public CandidateMergePreview preview(Jwt jwt, UUID targetId, UUID sourceId) {
        CandidateReader reader = access.requireReader(jwt);
        if (sourceId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Vui lòng chọn hồ sơ ứng viên cần gộp.");
        }
        requireDistinct(targetId, sourceId);
        // The write scope only decides whether the offer and talent pool blockers are shown; the merge checks it again.
        AccessScope write = AccessScope.write(permissions.forUser(reader.id()), PermissionModule.CANDIDATES);
        PreviewRead read = Objects.requireNonNull(reads.execute(status -> {
            var target = merges.profile(targetId).orElse(null);
            var source = merges.profile(sourceId).orElse(null);
            if (target == null || source == null) {
                status.setRollbackOnly();
                return new PreviewRead.Unknown();
            }
            List<CandidateMergeRepository.PreviewApplication> targetApplications = target.merged() ? List.of()
                    : merges.readableApplications(reader, targetId);
            List<CandidateMergeRepository.PreviewApplication> sourceApplications = source.merged() ? List.of()
                    : merges.readableApplications(reader, sourceId);
            List<UUID> refused = new ArrayList<>(2);
            if (!readable(reader, target, targetApplications)) refused.add(targetId);
            if (!readable(reader, source, sourceApplications)) refused.add(sourceId);
            if (!refused.isEmpty()) {
                status.setRollbackOnly();
                return new PreviewRead.Refused(refused);
            }
            List<CandidateMergePreview.Blocker> blockers = new ArrayList<>();
            if (target.merged()) blockers.add(CandidateMergePreview.Blocker.TARGET_MERGED);
            if (source.merged()) blockers.add(CandidateMergePreview.Blocker.SOURCE_MERGED);
            if (!merges.allWritable(reader, write, Set.copyOf(List.of(target.profileId(), source.profileId())))) {
                blockers.add(CandidateMergePreview.Blocker.NOT_WRITABLE);
            } else if (!source.merged()) {
                if (merges.hasActiveOffer(sourceId)) blockers.add(CandidateMergePreview.Blocker.SOURCE_ACTIVE_OFFER);
                if (merges.hasActiveTalentPoolEntry(sourceId)) {
                    blockers.add(CandidateMergePreview.Blocker.SOURCE_IN_TALENT_POOL);
                }
            }
            logShown(reader, targetId, targetApplications);
            logShown(reader, sourceId, sourceApplications);
            return new PreviewRead.Shown(CandidateMergePreview.of(target, targetApplications, source,
                    sourceApplications, blockers));
        }));
        return switch (read) {
            case PreviewRead.Shown shown -> shown.preview();
            case PreviewRead.Unknown unknown -> throw CandidateDuplicateService.candidateNotFound();
            case PreviewRead.Refused refused -> {
                // The read transaction has ended: each refusal commits alone (CandidateAccess.recordDenied).
                refused.candidateIds().forEach(candidate ->
                        access.recordDenied(reader, CandidateAccessKind.PROFILE, candidate, null));
                throw new AccessDeniedException("No readable application in one of the candidate profiles");
            }
        };
    }

    public CandidateMergeResult merge(Jwt jwt, UUID targetId, CandidateMergeRequest request) {
        UUID sourceId = request.sourceCandidateId();
        requireDistinct(targetId, sourceId);
        String reason = CandidateText.trim(request.reason());
        return write(() -> {
            CandidateWriteGuard.Writer writer = guard.lockWriter(jwt);
            guard.freshScopes(jwt, writer);
            Locked locked = lock(List.of(targetId, sourceId), Set.of(sourceId));
            CandidateMergeRepository.Candidate target = locked.candidate(targetId);
            CandidateMergeRepository.Candidate source = locked.candidate(sourceId);
            if (!locked.known(target) || !locked.known(source)) throw CandidateDuplicateService.candidateNotFound();
            CandidateWriteGuard.Scopes scopes = guard.freshScopes(jwt, writer);
            if (!merges.allWritable(scopes.reader(writer.id()), scopes.write(),
                    Set.copyOf(List.of(target.profileId(), source.profileId())))) {
                throw new AccessDeniedException("An application of the candidates is outside the caller's write scope");
            }
            if (target.merged() || source.merged()) {
                throw new ApiException(HttpStatus.CONFLICT, "CANDIDATE_ALREADY_MERGED",
                        "Hồ sơ ứng viên này đã được gộp vào một hồ sơ khác.");
            }
            if (merges.hasActiveOffer(sourceId)) {
                throw new ApiException(HttpStatus.CONFLICT, "CANDIDATE_MERGE_ACTIVE_OFFER",
                        "Hồ sơ cần gộp đang có offer chưa kết thúc. Vui lòng gộp theo chiều ngược lại hoặc chờ offer kết thúc.");
            }
            if (merges.hasActiveTalentPoolEntry(sourceId)) {
                throw new ApiException(HttpStatus.CONFLICT, "CANDIDATE_MERGE_TALENT_POOL",
                        "Hồ sơ cần gộp đang nằm trong kho tiềm năng. Vui lòng gộp theo chiều ngược lại hoặc gỡ khỏi kho trước.");
            }
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            List<CandidateMergeRepository.Link> moved = locked.links(sourceId);
            List<CandidateMergeRepository.Candidate> redirected = merges.mergedInto(sourceId);
            var merge = new CandidateMergeRecords.CandidateMerge(UUID.randomUUID(), targetId, sourceId, reason,
                    writer.id(), now, now.plus(UNDO_WINDOW), null, null);
            records.record(merge, moved.stream().map(link -> new CandidateMergeRecords.MovedApplication(
                            link.applicationId(), link.linkedAt())).toList(),
                    redirected.stream().map(candidate -> new CandidateMergeRecords.Redirect(candidate.id(),
                            candidate.mergedAt())).toList());
            merges.moveLinks(sourceId, targetId, now);
            merges.markMerged(sourceId, targetId, now);
            return new CandidateMergeResult(merge.id(), targetId, sourceId, now, merge.undoDeadline(),
                    moved.stream().map(CandidateMergeRepository.Link::applicationId).toList(),
                    redirected.stream().map(CandidateMergeRepository.Candidate::id).toList());
        });
    }

    // The links of these candidates and the candidates merged into the ones listed in redirectsOf, locked in the
    // house order: requisitions and applications first, then the candidate rows. Links and redirects are read again
    // under the candidate locks, which no new link or merge of these candidates passes, and whatever appeared in
    // between is locked too (late, so a rare deadlock answers 503 like any lock timeout).
    Locked lock(List<UUID> candidateIds, Set<UUID> redirectsOf) {
        List<CandidateMergeRepository.Link> before = links(candidateIds);
        merges.lockApplications(before);
        Set<UUID> rows = new HashSet<>(candidateIds);
        redirectsOf.forEach(candidate -> merges.mergedInto(candidate).forEach(redirect -> rows.add(redirect.id())));
        List<CandidateMergeRepository.Candidate> locked = new ArrayList<>(merges.lockCandidates(rows));
        Set<UUID> lockedIds = new HashSet<>(locked.stream().map(CandidateMergeRepository.Candidate::id).toList());
        Set<UUID> late = new HashSet<>();
        redirectsOf.forEach(candidate -> merges.mergedInto(candidate).forEach(redirect -> {
            if (!lockedIds.contains(redirect.id())) late.add(redirect.id());
        }));
        locked.addAll(merges.lockCandidates(late));
        List<CandidateMergeRepository.Link> after = links(candidateIds);
        Set<UUID> lockedApplications = new HashSet<>(before.stream()
                .map(CandidateMergeRepository.Link::applicationId).toList());
        merges.lockApplications(after.stream().filter(link -> !lockedApplications.contains(link.applicationId()))
                .toList());
        return new Locked(locked, after);
    }

    private List<CandidateMergeRepository.Link> links(List<UUID> candidateIds) {
        List<CandidateMergeRepository.Link> links = new ArrayList<>();
        candidateIds.forEach(candidate -> links.addAll(merges.links(candidate)));
        return links;
    }

    // The candidate rows and their links as they are under the locks.
    record Locked(List<CandidateMergeRepository.Candidate> candidates, List<CandidateMergeRepository.Link> allLinks) {
        CandidateMergeRepository.Candidate candidate(UUID id) {
            return candidates.stream().filter(candidate -> candidate.id().equals(id)).findFirst().orElse(null);
        }

        List<CandidateMergeRepository.Link> links(UUID candidateId) {
            return allLinks.stream().filter(link -> link.candidateId().equals(candidateId)).toList();
        }

        // A candidate exists for the APIs when it was merged or still has an application (CandidateProfileService).
        boolean known(CandidateMergeRepository.Candidate candidate) {
            return candidate != null && (candidate.merged() || !links(candidate.id()).isEmpty());
        }
    }

    // A side is readable through one of its own applications or, once merged, through the profile it went into.
    private boolean readable(CandidateReader reader, CandidateMergeRepository.Candidate candidate,
                             List<CandidateMergeRepository.PreviewApplication> shown) {
        return candidate.merged() ? merges.hasReadableApplication(reader, candidate.mergedInto()) : !shown.isEmpty();
    }

    private void logShown(CandidateReader reader, UUID candidateId,
                          List<CandidateMergeRepository.PreviewApplication> shown) {
        if (!shown.isEmpty()) {
            access.recordGranted(reader, CandidateAccessKind.PROFILE, candidateId,
                    shown.stream().map(CandidateMergeRepository.PreviewApplication::applicationId).toList());
        }
    }

    private static void requireDistinct(UUID targetId, UUID sourceId) {
        if (targetId.equals(sourceId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CANDIDATE_MERGE_SAME_CANDIDATE",
                    "Không thể gộp một hồ sơ ứng viên vào chính nó.");
        }
    }

    <T> T write(Supplier<T> work) {
        try {
            return Objects.requireNonNull(writes.execute(status -> work.get()));
        } catch (RuntimeException failure) {
            if (CandidateWriteGuard.busy(failure)) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CANDIDATE_MERGE_BUSY",
                        "Hồ sơ ứng viên đang được xử lý. Vui lòng thử lại.");
            }
            throw failure;
        }
    }

    // What the preview's read transaction found; the switch above is exhaustive over it.
    private sealed interface PreviewRead {
        record Shown(CandidateMergePreview preview) implements PreviewRead { }

        record Unknown() implements PreviewRead { }

        record Refused(List<UUID> candidateIds) implements PreviewRead { }
    }
}
