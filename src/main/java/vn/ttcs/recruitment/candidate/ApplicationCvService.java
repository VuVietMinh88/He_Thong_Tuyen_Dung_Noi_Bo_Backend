package vn.ttcs.recruitment.candidate;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.common.ApiException;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Task 362 (story S4-09, "CV xem trước ngay trong trang"; "chỉ recruiter phụ trách và hiring manager của vị trí đó mở
 * được; mọi truy cập được ghi nhật ký"): one application's CV, on task 363's CandidateAccess, in the order every
 * candidate endpoint follows:
 * <ol>
 * <li>requireReader: 401 for the token, session or account; 403 without a CANDIDATES read permission, before any
 * lookup. Not logged.</li>
 * <li>In one REPEATABLE READ transaction (read-write only because a granted read inserts its log row):
 * CandidateAccess.findApplication; an unknown application, or one without a candidate link (only possible with
 * hand-written SQL), is 404 APPLICATION_NOT_FOUND, not logged.</li>
 * <li>Outside the reader's scope (CandidateAccess.READABLE): the read transaction ends (rolled back), one DENIED CV row
 * naming the application is logged in its own short transaction, and the answer is 403 FORBIDDEN.</li>
 * <li>No CV row: 404 APPLICATION_CV_NOT_FOUND, not logged (nothing was shown).</li>
 * <li>Only now the bytes are read, one GRANTED CV row is written in the same transaction, and it commits.</li>
 * </ol>
 * Not transactional itself: the refusal is logged strictly after the read transaction, so a request never holds two
 * pool connections. The teammate's CV suggestions (task 334) must follow the same order and log.
 */
@Service
public class ApplicationCvService {
    private final CandidateAccess access;
    private final CandidateCvRepository cvs;
    private final TransactionTemplate reads;

    ApplicationCvService(CandidateAccess access, CandidateCvRepository cvs, PlatformTransactionManager transactions) {
        this.access = access;
        this.cvs = cvs;
        this.reads = new TransactionTemplate(transactions);
        // One snapshot for the existence check, the scope (team and department tree), the metadata and the bytes.
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public CvFile cv(Jwt jwt, UUID applicationId) {
        CandidateReader reader = access.requireReader(jwt);
        Read read = Objects.requireNonNull(reads.execute(status -> {
            var found = access.findApplication(reader, applicationId);
            if (found.isEmpty()) {
                status.setRollbackOnly();
                return new Read.Unknown();
            }
            ApplicationAccess application = found.get();
            if (!application.readable()) {
                status.setRollbackOnly();
                return new Read.Refused(application.candidateId());
            }
            var metadata = cvs.metadata(applicationId);
            if (metadata.isEmpty()) {
                status.setRollbackOnly();
                return new Read.NoCv();
            }
            byte[] content = cvs.content(applicationId);
            access.recordGranted(reader, CandidateAccessKind.CV, application.candidateId(), List.of(applicationId));
            return new Read.Shown(new CvFile(metadata.get().filename(), metadata.get().contentType(), content));
        }));
        return switch (read) {
            case Read.Shown shown -> shown.cv();
            case Read.Unknown unknown -> throw new ApiException(HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND",
                    "Không tìm thấy hồ sơ ứng tuyển.");
            case Read.NoCv noCv -> throw new ApiException(HttpStatus.NOT_FOUND, "APPLICATION_CV_NOT_FOUND",
                    "Hồ sơ ứng tuyển này không có tệp CV.");
            case Read.Refused refused -> {
                // The read transaction has ended: the refusal commits alone (CandidateAccess.recordDenied).
                access.recordDenied(reader, CandidateAccessKind.CV, refused.candidateId(), applicationId);
                throw new AccessDeniedException("The application is outside the reader's scope");
            }
        };
    }

    // What the read transaction found; the switch above is exhaustive over it.
    private sealed interface Read {
        record Shown(CvFile cv) implements Read { }

        record Unknown() implements Read { }

        record Refused(UUID candidateId) implements Read { }

        record NoCv() implements Read { }
    }
}
