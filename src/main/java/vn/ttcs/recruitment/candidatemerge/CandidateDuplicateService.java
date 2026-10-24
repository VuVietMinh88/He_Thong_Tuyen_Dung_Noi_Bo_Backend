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
import vn.ttcs.recruitment.common.ApiException;

import java.util.Objects;
import java.util.UUID;

/**
 * Task 367 (story S5-01, "Là Nhân viên tuyển dụng, tôi muốn được cảnh báo và gộp hồ sơ ứng viên trùng"): the
 * candidates that may be the same person as one candidate, built on task 363's CandidateAccess in the order every
 * candidate endpoint follows:
 * <ol>
 * <li>requireReader: 401 for the token, session or account; 403 without a CANDIDATES read permission, before any
 * lookup. Not logged.</li>
 * <li>In one REPEATABLE READ transaction: a candidate without any linked application is unknown, 404
 * CANDIDATE_NOT_FOUND, not logged.</li>
 * <li>None of its applications readable: the read transaction ends (rolled back), one DENIED PROFILE row without an
 * application is logged, 403.</li>
 * <li>Otherwise the matches (CandidateDuplicateRepository) are listed, and every listed candidate gets one GRANTED
 * PROFILE row per listed application in the same transaction, as the talent pool search logs its entries. The
 * candidate asked about discloses nothing of its own, so it is not logged again.</li>
 * </ol>
 * The suggestion is the warning of the story: it is read when a recruiter opens a candidate, so an application that
 * arrived a moment ago is already compared. Merging (task 368) is a separate, explicit write.
 */
@Service
public class CandidateDuplicateService {
    private final CandidateAccess access;
    private final CandidateDuplicateRepository duplicates;
    private final TransactionTemplate reads;

    CandidateDuplicateService(CandidateAccess access, CandidateDuplicateRepository duplicates,
                              PlatformTransactionManager transactions) {
        this.access = access;
        this.duplicates = duplicates;
        this.reads = new TransactionTemplate(transactions);
        // One snapshot for the existence check, the scope and both sides of the comparison.
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public CandidateDuplicatesView duplicates(Jwt jwt, UUID candidateId) {
        CandidateReader reader = access.requireReader(jwt);
        Read read = Objects.requireNonNull(reads.execute(status -> {
            if (!duplicates.hasApplications(candidateId)) {
                status.setRollbackOnly();
                return new Read.Unknown();
            }
            if (!duplicates.hasReadableApplication(reader, candidateId)) {
                status.setRollbackOnly();
                return new Read.Refused();
            }
            CandidateDuplicatesView view = CandidateDuplicatesView.of(candidateId,
                    duplicates.duplicates(reader, candidateId));
            view.duplicates().forEach(duplicate -> access.recordGranted(reader, CandidateAccessKind.PROFILE,
                    duplicate.candidateId(), duplicate.applications().stream()
                            .map(CandidateDuplicatesView.Application::applicationId).toList()));
            return new Read.Shown(view);
        }));
        return switch (read) {
            case Read.Shown shown -> shown.view();
            case Read.Unknown unknown -> throw candidateNotFound();
            case Read.Refused refused -> {
                // The read transaction has ended: the refusal commits alone (CandidateAccess.recordDenied).
                access.recordDenied(reader, CandidateAccessKind.PROFILE, candidateId, null);
                throw new AccessDeniedException("No readable application in this candidate profile");
            }
        };
    }

    // The unified profile's answer for an unknown candidate (CandidateProfileService).
    static ApiException candidateNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "CANDIDATE_NOT_FOUND", "Không tìm thấy hồ sơ ứng viên.");
    }

    // What the read transaction found; the switch above is exhaustive over it.
    private sealed interface Read {
        record Shown(CandidateDuplicatesView view) implements Read { }

        record Unknown() implements Read { }

        record Refused() implements Read { }
    }
}
