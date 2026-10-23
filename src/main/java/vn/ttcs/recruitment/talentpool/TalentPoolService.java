package vn.ttcs.recruitment.talentpool;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.application.ApplicationStage;
import vn.ttcs.recruitment.candidate.ApplicationAccess;
import vn.ttcs.recruitment.candidate.CandidateAccess;
import vn.ttcs.recruitment.candidate.CandidateAccessKind;
import vn.ttcs.recruitment.candidate.CandidateLabels;
import vn.ttcs.recruitment.candidate.CandidateReader;
import vn.ttcs.recruitment.candidate.CandidateWriteGuard;
import vn.ttcs.recruitment.common.ApiException;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Task 398 (story S5-06, "Ứng viên bị loại ở vòng cuối được đánh dấu đưa vào kho tiềm năng kèm lý do"): adding a
 * candidate to the talent pool and reading an entry.
 * <p>
 * Adding is a candidate write about the source application: the caller's account and session are locked, then the
 * requisition and the application FOR NO KEY UPDATE, with CANDIDATES read and write and the record scope (a SCOPED
 * writer must be assigned to the requisition now) checked again after every lock wait. Only an application that is
 * REJECTED now, with evidence that the rejection came at a final stage (TalentPoolRepository.evidence), qualifies
 * (409 TALENT_POOL_NOT_ELIGIBLE); a candidate has at most one active entry (409 TALENT_POOL_ALREADY_ADDED). Nothing
 * about the application or its stage changes.
 * <p>
 * Reading an entry follows the candidate profile rule on its source application: 404 unknown, 403 outside the scope
 * (logged as a refused PROFILE read), one granted PROFILE row otherwise.
 * <p>
 * Task 399 (story S5-06, "Kho tìm kiếm được theo chức danh quan tâm và kỹ năng"): the search lists the active entries
 * whose source application the reader may read, logging each one shown. A removal is the same candidate write as an
 * addition, on the entry's source application; it keeps the entry with who removed it and when (409
 * TALENT_POOL_ENTRY_REMOVED when already removed), so the candidate may be added again later.
 */
@Service
public class TalentPoolService {
    static final int MAX_TITLES = 10;
    static final int MAX_TITLE_LENGTH = 100;
    static final int MAX_SKILLS = 30;
    static final int MAX_SKILL_LENGTH = 60;
    private final TalentPoolRepository pool;
    private final CandidateAccess access;
    private final CandidateWriteGuard guard;
    private final Clock clock;
    private final TransactionTemplate reads;
    private final TransactionTemplate writes;

    TalentPoolService(TalentPoolRepository pool, CandidateAccess access, CandidateWriteGuard guard, Clock clock,
                      PlatformTransactionManager transactions) {
        this.pool = pool;
        this.access = access;
        this.guard = guard;
        this.clock = clock;
        reads = new TransactionTemplate(transactions);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        writes = new TransactionTemplate(transactions);
        writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        writes.setTimeout(5);
    }

    public TalentPoolEntryView add(Jwt jwt, TalentPoolAddRequest request) {
        List<CandidateLabels.Label> titles = CandidateLabels.labels(request.titleInterests(), "titleInterests",
                MAX_TITLES, MAX_TITLE_LENGTH);
        List<CandidateLabels.Label> skills = CandidateLabels.labels(request.skills(), "skills", MAX_SKILLS,
                MAX_SKILL_LENGTH);
        UUID application = request.applicationId();
        return write(() -> {
            CandidateWriteGuard.Writer writer = guard.lockWriter(jwt);
            guard.freshScopes(jwt, writer);
            UUID requisition = guard.lockRequisitionOf(application);
            guard.requireWritable(jwt, writer, requisition, application);
            ApplicationStage stage = guard.lockApplication(application);
            ApplicationAccess found = guard.requireWritable(jwt, writer, requisition, application);
            TalentPoolEvidence evidence = stage != ApplicationStage.REJECTED ? null
                    : pool.evidence(application).orElse(null);
            if (evidence == null) {
                throw new ApiException(HttpStatus.CONFLICT, "TALENT_POOL_NOT_ELIGIBLE",
                        "Chỉ hồ sơ đã bị loại ở vòng cuối (sau phỏng vấn hoặc offer) mới được đưa vào kho tiềm năng.");
            }
            UUID entry = UUID.randomUUID();
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            if (!pool.insert(entry, found.candidateId(), application, evidence, request.reason().strip(), writer.id(),
                    now)) {
                throw new ApiException(HttpStatus.CONFLICT, "TALENT_POOL_ALREADY_ADDED",
                        "Ứng viên này đã có trong kho tiềm năng.");
            }
            pool.insertInterests(entry, TalentPoolRepository.TITLE, titles);
            pool.insertInterests(entry, TalentPoolRepository.SKILL, skills);
            CandidateReader reader = guard.freshScopes(jwt, writer).reader(writer.id());
            access.recordGranted(reader, CandidateAccessKind.PROFILE, found.candidateId(), List.of(application));
            return pool.find(entry).orElseThrow();
        });
    }

    public TalentPoolPage list(Jwt jwt, String title, List<String> skills, int page, int size) {
        CandidateReader reader = access.requireReader(jwt);
        TalentPoolSearch search = TalentPoolSearch.of(title, skills, page, size);
        return Objects.requireNonNull(reads.execute(status -> {
            long total = pool.count(reader, search);
            List<TalentPoolEntryView> items = pool.page(reader, search);
            items.stream().collect(java.util.stream.Collectors.groupingBy(TalentPoolEntryView::candidateId))
                    .forEach((candidate, shown) -> access.recordGranted(reader, CandidateAccessKind.PROFILE, candidate,
                            shown.stream().map(TalentPoolEntryView::sourceApplicationId).distinct().toList()));
            return new TalentPoolPage(items, total, page, size, search.offset() + items.size() < total);
        }));
    }

    public void remove(Jwt jwt, UUID entryId) {
        write(() -> {
            CandidateWriteGuard.Writer writer = guard.lockWriter(jwt);
            guard.freshScopes(jwt, writer);
            UUID application = pool.sourceOf(entryId).orElseThrow(TalentPoolService::notFound);
            UUID requisition = guard.lockRequisitionOf(application);
            guard.requireWritable(jwt, writer, requisition, application);
            guard.lockApplication(application);
            guard.requireWritable(jwt, writer, requisition, application);
            if (!pool.lockActive(entryId)) {
                throw new ApiException(HttpStatus.CONFLICT, "TALENT_POOL_ENTRY_REMOVED",
                        "Hồ sơ này đã được gỡ khỏi kho tiềm năng.");
            }
            guard.requireWritable(jwt, writer, requisition, application);
            pool.remove(entryId, writer.id(), clock.instant().truncatedTo(ChronoUnit.MICROS));
            return Boolean.TRUE;
        });
    }

    public TalentPoolEntryView get(Jwt jwt, UUID entryId) {
        CandidateReader reader = access.requireReader(jwt);
        Read read = Objects.requireNonNull(reads.execute(status -> {
            var visibility = pool.visibility(reader, entryId).orElseThrow(TalentPoolService::notFound);
            if (!visibility.readable()) {
                status.setRollbackOnly();
                return new Read(null, visibility);
            }
            access.recordGranted(reader, CandidateAccessKind.PROFILE, visibility.candidateId(),
                    List.of(visibility.sourceApplicationId()));
            return new Read(pool.find(entryId).orElseThrow(), null);
        }));
        if (read.denied() != null) {
            access.recordDenied(reader, CandidateAccessKind.PROFILE, read.denied().candidateId(),
                    read.denied().sourceApplicationId());
            throw new AccessDeniedException("Talent pool entry is outside the reader's scope");
        }
        return read.entry();
    }

    private record Read(TalentPoolEntryView entry, TalentPoolRepository.Visibility denied) { }

    <T> T write(java.util.function.Supplier<T> work) {
        try {
            return Objects.requireNonNull(writes.execute(status -> work.get()));
        } catch (RuntimeException failure) {
            if (CandidateWriteGuard.busy(failure)) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "TALENT_POOL_BUSY",
                        "Hồ sơ đang được xử lý. Vui lòng thử lại.");
            }
            throw failure;
        }
    }

    static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "TALENT_POOL_ENTRY_NOT_FOUND",
                "Không tìm thấy hồ sơ trong kho tiềm năng.");
    }
}
