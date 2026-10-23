package vn.ttcs.recruitment.evaluationreport;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.candidate.CandidateAccess;
import vn.ttcs.recruitment.candidate.CandidateAccessKind;
import vn.ttcs.recruitment.candidate.CandidateReader;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.evaluation.EvaluationView;
import vn.ttcs.recruitment.security.AccessScope;
import vn.ttcs.recruitment.security.PermissionModule;
import vn.ttcs.recruitment.security.PermissionService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class EvaluationReportService {
    private final CandidateAccess candidates;
    private final PermissionService permissions;
    private final NamedParameterJdbcTemplate jdbc;
    private final EvaluationEvidenceService evidence;
    private final TransactionTemplate reads;

    public EvaluationReportService(CandidateAccess candidates, PermissionService permissions,
            NamedParameterJdbcTemplate jdbc, EvaluationEvidenceService evidence, PlatformTransactionManager transactions) {
        this.candidates = candidates; this.permissions = permissions; this.jdbc = jdbc; this.evidence = evidence;
        reads = new TransactionTemplate(transactions);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        // The response and its granted-access audit share a snapshot and commit together.
        reads.setTimeout(10);
    }

    public record Summary(UUID applicationId, UUID latestInterviewId, String evidenceHash,
                          EvaluationAggregation.Result aggregate, List<EvaluationView> forms) { }
    public record ComparedCandidate(UUID applicationId, UUID candidateId, UUID requisitionId, String candidateName,
                                    UUID finalInterviewId, EvaluationAggregation.Result aggregate) { }
    public record Comparison(UUID positionId, List<ComparedCandidate> items, long totalElements, int page, int size) { }
    private record Application(UUID id, UUID candidateId, UUID requisitionId, String name) { }

    public Summary summary(Jwt jwt, UUID applicationId) {
        return read(() -> {
            CandidateReader reader = reader(jwt);
            var application = candidates.findApplication(reader, applicationId).orElseThrow(EvaluationReportService::notFound);
            if (!application.readable()) throw new Denied(reader, application.candidateId(), applicationId);
            var result = evidence.load(applicationId, reader.id(), false);
            candidates.recordGranted(reader, CandidateAccessKind.PROFILE, application.candidateId(), List.of(applicationId));
            return new Summary(applicationId, result.latestInterviewId(), result.hash(),
                    EvaluationAggregation.aggregate(result.submittedForms()), result.submittedForms());
        });
    }

    public Comparison compare(Jwt jwt, UUID positionId, int page, int size) {
        if (page < 0 || page > 100000 || size < 1 || size > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Trang phải từ 0 và kích thước từ 1 đến 100.");
        }
        return read(() -> {
            CandidateReader reader = reader(jwt);
            var parameters = candidates.parameters(reader).addValue("position", positionId);
            if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM positions WHERE id=:position)", parameters, Boolean.class))) {
                throw new ApiException(HttpStatus.NOT_FOUND, "POSITION_NOT_FOUND", "Không tìm thấy chức danh.");
            }
            // Scope filtering happens before aggregation and totals. A pending newer round never falls back to old scores.
            List<Application> applications = jdbc.query("""
                    SELECT a.id,ca.candidate_id,a.requisition_id,a.full_name FROM job_applications a
                    JOIN recruitment_requisitions r ON r.id=a.requisition_id
                    JOIN candidate_applications ca ON ca.application_id=a.id
                    WHERE r.position_id=:position AND a.stage='INTERVIEW' AND
                    """ + CandidateAccess.READABLE + " ORDER BY a.id LIMIT 1001", parameters,
                    (r, n) -> new Application(r.getObject("id", UUID.class), r.getObject("candidate_id", UUID.class),
                            r.getObject("requisition_id", UUID.class), r.getString("full_name")));
            if (applications.size() > 1000) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "COMPARISON_TOO_LARGE", "Chức danh có quá nhiều hồ sơ đang phỏng vấn để so sánh cùng lúc.");
            }
            List<ComparedCandidate> rows = new ArrayList<>();
            for (Application application : applications) {
                // The loader omits blind final-round data without throwing through a joined transaction.
                var loaded = evidence.loadForComparison(application.id(), reader.id());
                List<EvaluationView> finalForms = loaded.submittedForms().stream()
                        .filter(f -> f.interviewId().equals(loaded.latestInterviewId())).toList();
                if (finalForms.isEmpty()) continue;
                rows.add(new ComparedCandidate(application.id(), application.candidateId(), application.requisitionId(), application.name(),
                        loaded.latestInterviewId(), EvaluationAggregation.aggregate(finalForms)));
            }
            rows.sort(Comparator.comparing((ComparedCandidate r) -> r.aggregate().averageTotal()).reversed()
                    .thenComparing(r -> r.applicationId().toString()));
            int start = (int) Math.min((long) page * size, rows.size());
            List<ComparedCandidate> result = List.copyOf(rows.subList(start, Math.min(start + size, rows.size())));
            for (ComparedCandidate row : result) {
                candidates.recordGranted(reader, CandidateAccessKind.PROFILE, row.candidateId(), List.of(row.applicationId()));
            }
            return new Comparison(positionId, result, rows.size(), page, size);
        });
    }

    private CandidateReader reader(Jwt jwt) {
        CandidateReader reader = candidates.requireReader(jwt);
        AccessScope evaluation = AccessScope.read(permissions.forUser(reader.id()), PermissionModule.EVALUATIONS).orDeny();
        return new CandidateReader(reader.id(), reader.scope() == AccessScope.SCOPED || evaluation == AccessScope.SCOPED
                ? AccessScope.SCOPED : AccessScope.ALL);
    }
    private <T> T read(Supplier<T> body) {
        try { return Objects.requireNonNull(reads.execute(status -> body.get())); }
        catch (Denied denied) {
            candidates.recordDenied(denied.reader, CandidateAccessKind.PROFILE, denied.candidate, null);
            throw new AccessDeniedException("Application outside evaluation scope");
        }
    }
    private static ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND", "Không tìm thấy hồ sơ ứng tuyển."); }
    private static final class Denied extends RuntimeException {
        final CandidateReader reader; final UUID candidate;
        Denied(CandidateReader reader, UUID candidate, UUID application) { this.reader = reader; this.candidate = candidate; }
    }
}
