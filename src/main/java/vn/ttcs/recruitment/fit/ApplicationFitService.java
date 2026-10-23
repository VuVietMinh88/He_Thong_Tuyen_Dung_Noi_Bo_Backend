package vn.ttcs.recruitment.fit;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
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
 * Task 413 (story S5-09): an application's fit and the qualifications staff record for it.
 * <p>
 * Reading follows the candidate profile rule: 401/403 before any lookup, 404 for an unknown application, 403 outside
 * CandidateAccess.READABLE (logged as a refused PROFILE read), otherwise one granted PROFILE row.
 * <p>
 * Recording qualifications is a candidate write, like a screening rating: the caller's account and session are locked,
 * then the requisition and the application FOR NO KEY UPDATE, with CANDIDATES read and write and the record scope (a
 * SCOPED writer must be assigned to the requisition) checked again after every lock wait; expectedVersion must match
 * (409 QUALIFICATIONS_CHANGED). The fit result is recomputed in the same transaction. The application's stage is never
 * read for a decision and never changed: the score is advice for sorting only. A stored result that names older
 * criteria (a criteria save recomputes nothing, FitRefreshWorker catches up) is shown evaluated live, current=false.
 */
@Service
public class ApplicationFitService {
    static final int MAX_SKILLS = 50;
    static final int MAX_SKILL_LENGTH = 60;
    private final CandidateAccess access;
    private final CandidateWriteGuard guard;
    private final FitRepository fits;
    private final FitResults results;
    private final RequiredCriteriaRepository criteria;
    private final Clock clock;
    private final TransactionTemplate reads;
    private final TransactionTemplate writes;

    ApplicationFitService(CandidateAccess access, CandidateWriteGuard guard, FitRepository fits, FitResults results,
                          RequiredCriteriaRepository criteria, Clock clock, PlatformTransactionManager transactions) {
        this.access = access;
        this.guard = guard;
        this.fits = fits;
        this.results = results;
        this.criteria = criteria;
        this.clock = clock;
        reads = new TransactionTemplate(transactions);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        writes = new TransactionTemplate(transactions);
        writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        writes.setTimeout(5);
    }

    public ApplicationFitView get(Jwt jwt, UUID applicationId) {
        CandidateReader reader = access.requireReader(jwt);
        Read read = Objects.requireNonNull(reads.execute(status -> {
            ApplicationAccess found = access.findApplication(reader, applicationId)
                    .orElseThrow(CandidateWriteGuard::applicationNotFound);
            if (!found.readable()) {
                status.setRollbackOnly();
                return new Read(null, found.candidateId());
            }
            ApplicationFitView view = view(applicationId);
            access.recordGranted(reader, CandidateAccessKind.PROFILE, found.candidateId(), List.of(applicationId));
            return new Read(view, null);
        }));
        if (read.deniedCandidate() != null) {
            access.recordDenied(reader, CandidateAccessKind.PROFILE, read.deniedCandidate(), applicationId);
            throw new AccessDeniedException("Application fit is outside the reader's scope");
        }
        return read.view();
    }

    private record Read(ApplicationFitView view, UUID deniedCandidate) { }

    public ApplicationFitView record(Jwt jwt, UUID applicationId, QualificationsRequest request) {
        List<CandidateLabels.Label> skills = CandidateLabels.labels(request.skills(), "skills", MAX_SKILLS,
                MAX_SKILL_LENGTH);
        try {
            return Objects.requireNonNull(writes.execute(status -> {
                CandidateWriteGuard.Writer writer = guard.lockWriter(jwt);
                guard.freshScopes(jwt, writer);
                UUID requisition = guard.lockRequisitionOf(applicationId);
                guard.requireWritable(jwt, writer, requisition, applicationId);
                guard.lockApplication(applicationId);
                ApplicationAccess found = guard.requireWritable(jwt, writer, requisition, applicationId);
                long version = fits.lockQualificationsVersion(applicationId);
                if (version != request.expectedVersion()) {
                    throw new ApiException(HttpStatus.CONFLICT, "QUALIFICATIONS_CHANGED",
                            "Thông tin năng lực của hồ sơ vừa được thay đổi ở nơi khác. Vui lòng tải lại.");
                }
                Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
                fits.saveQualifications(applicationId, request.experienceYears(), request.educationLevel(), skills,
                        request.skills() != null, writer.id(), now, version + 1);
                results.evaluate(applicationId, now);
                // The position lock may have waited: the caller is checked once more before anything is shown.
                CandidateWriteGuard.Scopes scopes = guard.freshScopes(jwt, writer);
                found = guard.requireWritable(jwt, writer, requisition, applicationId);
                ApplicationFitView view = view(applicationId);
                access.recordGranted(scopes.reader(writer.id()), CandidateAccessKind.PROFILE, found.candidateId(),
                        List.of(applicationId));
                return view;
            }));
        } catch (RuntimeException failure) {
            if (CandidateWriteGuard.busy(failure)) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "QUALIFICATIONS_BUSY",
                        "Hồ sơ đang được xử lý. Vui lòng thử lại.");
            }
            throw failure;
        }
    }

    private ApplicationFitView view(UUID applicationId) {
        UUID position = fits.positionOf(applicationId).orElseThrow(CandidateWriteGuard::applicationNotFound);
        RequiredCriteria required = criteria.find(position).orElse(null);
        Qualifications qualifications = fits.qualifications(applicationId).orElse(null);
        long criteriaVersion = required == null ? 0 : required.version();
        long qualificationsVersion = qualifications == null ? 0 : qualifications.version();
        var stored = fits.result(applicationId);
        boolean current = stored.isPresent() && stored.get().criteriaVersion() == criteriaVersion
                && stored.get().qualificationsVersion() == qualificationsVersion
                && stored.get().positionId().equals(position);
        ApplicationFitView.FitView fit = current
                ? ApplicationFitView.FitView.of(stored.get().result(), stored.get().evaluatedAt(), true)
                : ApplicationFitView.FitView.of(FitEvaluator.evaluate(required, qualifications), clock.instant(), false);
        return new ApplicationFitView(applicationId, position, ApplicationFitView.QualificationsView.of(qualifications),
                RequiredCriteriaView.of(position, required), fit);
    }
}
