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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * <p>
 * Task 370 (story S5-01, "Gộp nhầm có thể hoàn tác trong 24 giờ"): the undo is the same candidate write, over the
 * target's applications (the moved ones among them), with the target, the source and the candidates the merge
 * re-pointed locked FOR UPDATE and then the merge row. 404 for an unknown merge, 403 when the caller cannot write
 * every application of the target, then 409 when it was undone already, when its deadline has passed (the deadline
 * itself is too late), or when something changed since that an undo cannot put back (CANDIDATE_MERGE_CHANGED): the
 * target was merged into another candidate, a moved application belongs to another candidate now, or an offer or a
 * talent pool entry was created since for an application that would go back (both name the target for good). The
 * undo links back the applications the merge moved, with their old link times, and the ones that joined the target
 * since through an email of the source or of a candidate it had re-pointed (they would have joined the source), points
 * those candidates at the source again, makes the source a profile of its own and marks the merge undone. The history
 * of one candidate is a read like the preview: 404 unknown, 403 without a readable application (logged).
 */
@Service
public class CandidateMergeService {
    static final Duration UNDO_WINDOW = Duration.ofHours(24);
    // The merges of one candidate listed at most; one more is read only to report that the list was cut.
    static final int MAX_HISTORY = 50;
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
        Read<CandidateMergePreview> read = Objects.requireNonNull(reads.execute(status -> {
            var target = merges.profile(targetId).orElse(null);
            var source = merges.profile(sourceId).orElse(null);
            if (target == null || source == null) {
                status.setRollbackOnly();
                return new Read.Unknown<>();
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
                return new Read.Refused<>(refused);
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
            return new Read.Shown<>(CandidateMergePreview.of(target, targetApplications, source,
                    sourceApplications, blockers));
        }));
        return shown(read, reader);
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

    public CandidateMergeUndoResult undo(Jwt jwt, UUID mergeId) {
        return write(() -> {
            CandidateWriteGuard.Writer writer = guard.lockWriter(jwt);
            guard.freshScopes(jwt, writer);
            CandidateMergeRecords.CandidateMerge recorded = records.find(mergeId)
                    .orElseThrow(CandidateMergeService::mergeNotFound);
            UUID targetId = recorded.targetCandidateId();
            UUID sourceId = recorded.sourceCandidateId();
            List<CandidateMergeRecords.Redirect> redirects = records.redirects(mergeId);
            List<UUID> rows = new ArrayList<>(List.of(targetId, sourceId));
            redirects.forEach(redirect -> rows.add(redirect.candidateId()));
            Locked locked = lock(rows, Set.of());
            // Two undos of one merge pass the candidate locks one after the other; the second sees the first's mark.
            CandidateMergeRecords.CandidateMerge merge = records.lock(mergeId)
                    .orElseThrow(CandidateMergeService::mergeNotFound);
            CandidateMergeRepository.Candidate target = locked.candidate(targetId);
            CandidateMergeRepository.Candidate source = locked.candidate(sourceId);
            CandidateWriteGuard.Scopes scopes = guard.freshScopes(jwt, writer);
            if (!merges.allWritable(scopes.reader(writer.id()), scopes.write(),
                    Set.copyOf(List.of(target.profileId(), source.profileId())))) {
                throw new AccessDeniedException("An application of the candidates is outside the caller's write scope");
            }
            if (merge.undone()) {
                throw new ApiException(HttpStatus.CONFLICT, "CANDIDATE_MERGE_ALREADY_UNDONE",
                        "Lần gộp này đã được hoàn tác.");
            }
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            if (!merge.undoableAt(now)) {
                throw new ApiException(HttpStatus.CONFLICT, "CANDIDATE_MERGE_UNDO_EXPIRED",
                        "Đã quá 24 giờ kể từ khi gộp nên không thể hoàn tác.");
            }
            List<CandidateMergeRecords.MovedApplication> moved = records.movedApplications(mergeId);
            Set<UUID> movedIds = new HashSet<>(moved.stream()
                    .map(CandidateMergeRecords.MovedApplication::applicationId).toList());
            // A moved application deleted since (its link cascaded) has nothing to put back.
            Map<UUID, UUID> linkedNow = merges.linkedCandidates(movedIds);
            List<CandidateMergeRepository.Link> back = moved.stream()
                    .filter(application -> linkedNow.containsKey(application.applicationId()))
                    .map(application -> new CandidateMergeRepository.Link(sourceId, application.applicationId(), null,
                            application.linkedAt())).toList();
            Set<String> emails = new HashSet<>();
            emails.add(source.normalizedEmail());
            Map<UUID, Instant> repointed = new LinkedHashMap<>();
            for (CandidateMergeRecords.Redirect redirect : redirects) {
                CandidateMergeRepository.Candidate candidate = locked.candidate(redirect.candidateId());
                emails.add(candidate.normalizedEmail());
                repointed.put(candidate.id(), redirect.mergedAt());
            }
            List<CandidateMergeRepository.Link> later = merges.linkedSince(targetId, merge.mergedAt(), emails).stream()
                    .filter(link -> !movedIds.contains(link.applicationId())).toList();
            List<UUID> restored = new ArrayList<>(back.stream().map(CandidateMergeRepository.Link::applicationId).toList());
            later.forEach(link -> restored.add(link.applicationId()));
            boolean changed = target.merged() || !targetId.equals(source.mergedInto())
                    || linkedNow.values().stream().anyMatch(candidate -> !candidate.equals(targetId))
                    || repointed.keySet().stream().anyMatch(id -> !targetId.equals(locked.candidate(id).mergedInto()))
                    || merges.hasOfferSince(restored, merge.mergedAt())
                    || merges.hasTalentPoolEntrySince(restored, merge.mergedAt());
            if (changed) {
                throw new ApiException(HttpStatus.CONFLICT, "CANDIDATE_MERGE_CHANGED",
                        "Hồ sơ đã thay đổi sau khi gộp (gộp tiếp, tạo offer hoặc đưa vào kho tiềm năng) nên không thể hoàn tác.");
            }
            merges.relink(sourceId, back);
            merges.relink(sourceId, later);
            merges.unmarkMerged(sourceId, repointed);
            if (!records.markUndone(mergeId, writer.id(), now)) {
                throw new IllegalStateException("The locked merge was undone by someone else");
            }
            return new CandidateMergeUndoResult(mergeId, targetId, sourceId, now, List.copyOf(restored),
                    List.copyOf(repointed.keySet()));
        });
    }

    public CandidateMergeHistory history(Jwt jwt, UUID candidateId) {
        CandidateReader reader = access.requireReader(jwt);
        Read<CandidateMergeHistory> read = Objects.requireNonNull(reads.execute(status -> {
            var candidate = merges.profile(candidateId).orElse(null);
            if (candidate == null) {
                status.setRollbackOnly();
                return new Read.Unknown<>();
            }
            if (!merges.hasReadableApplication(reader, candidate.profileId())) {
                status.setRollbackOnly();
                return new Read.Refused<>(List.of(candidateId));
            }
            List<CandidateMergeRecords.CandidateMerge> found = records.involving(candidateId, MAX_HISTORY + 1);
            Set<UUID> staff = new HashSet<>();
            found.forEach(merge -> {
                staff.add(merge.mergedBy());
                if (merge.undoneBy() != null) staff.add(merge.undoneBy());
            });
            return new Read.Shown<>(CandidateMergeHistory.of(candidateId, found, MAX_HISTORY, merges.staffNames(staff),
                    merges.movedCounts(found.stream().map(CandidateMergeRecords.CandidateMerge::id).toList()),
                    clock.instant()));
        }));
        return shown(read, reader);
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

    // The value a read transaction found, or its answer: 404 for an unknown candidate, 403 for refused ones.
    private <T> T shown(Read<T> read, CandidateReader reader) {
        return switch (read) {
            case Read.Shown<T> shown -> shown.value();
            case Read.Unknown<T> unknown -> throw CandidateDuplicateService.candidateNotFound();
            case Read.Refused<T> refused -> {
                // The read transaction has ended: each refusal commits alone (CandidateAccess.recordDenied).
                refused.candidateIds().forEach(candidate ->
                        access.recordDenied(reader, CandidateAccessKind.PROFILE, candidate, null));
                throw new AccessDeniedException("No readable application in a candidate profile");
            }
        };
    }

    static ApiException mergeNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "CANDIDATE_MERGE_NOT_FOUND", "Không tìm thấy lần gộp hồ sơ.");
    }

    // What a read transaction found; the switch in shown is exhaustive over it.
    private sealed interface Read<T> {
        record Shown<T>(T value) implements Read<T> { }

        record Unknown<T>() implements Read<T> { }

        record Refused<T>(List<UUID> candidateIds) implements Read<T> { }
    }
}
