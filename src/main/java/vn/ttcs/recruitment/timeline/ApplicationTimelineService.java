package vn.ttcs.recruitment.timeline;

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
import vn.ttcs.recruitment.common.ApiException;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Reads one application's internal history, with the same record scope and audit as its profile. */
@Service
public class ApplicationTimelineService {
    private final ApplicationTimelineRepository timeline;
    private final CandidateAccess access;
    private final TransactionTemplate reads;

    public ApplicationTimelineService(ApplicationTimelineRepository timeline, CandidateAccess access,
                                      PlatformTransactionManager transactions) {
        this.timeline = timeline;
        this.access = access;
        reads = new TransactionTemplate(transactions);
        // Authorization, the count and the page see one snapshot. The granted audit also writes in it.
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public ApplicationTimelinePage get(Jwt jwt, UUID applicationId, int page, int size) {
        CandidateReader reader = access.requireReader(jwt);
        if (page < 0 || page > 1_000_000 || size < 1 || size > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TIMELINE_PAGE_INVALID",
                    "Trang phải từ 0 đến 1000000; kích thước trang từ 1 đến 100.");
        }
        Read result = Objects.requireNonNull(reads.execute(status -> {
            var application = access.findApplication(reader, applicationId).orElseThrow(() ->
                    new ApiException(HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND", "Không tìm thấy hồ sơ ứng tuyển."));
            if (!application.readable()) {
                status.setRollbackOnly();
                return new Read(null, application.candidateId());
            }
            ApplicationTimelinePage history = timeline.find(applicationId, page, size);
            access.recordGranted(reader, CandidateAccessKind.PROFILE, application.candidateId(), List.of(applicationId));
            return new Read(history, null);
        }));
        if (result.deniedCandidate() != null) {
            // CandidateAccess commits refusals separately, after the read transaction has finished.
            access.recordDenied(reader, CandidateAccessKind.PROFILE, result.deniedCandidate(), applicationId);
            throw new AccessDeniedException("Application timeline is outside the reader's scope");
        }
        return result.page();
    }

    private record Read(ApplicationTimelinePage page, UUID deniedCandidate) { }
}
