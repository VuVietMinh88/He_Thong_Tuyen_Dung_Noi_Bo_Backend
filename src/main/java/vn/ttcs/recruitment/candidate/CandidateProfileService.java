package vn.ttcs.recruitment.candidate;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.screening.ScreeningRepository;
import vn.ttcs.recruitment.screening.ScreeningView;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.HashMap;
import java.util.Map;

/**
 * Task 361 (story S4-09, "Một trang gồm thông tin cá nhân, CV xem trước…, lịch sử ứng tuyển các vị trí, nhãn và ghi
 * chú; hiển thị đầy đủ các lần ứng tuyển trước đây của cùng người này"): the unified profile of one candidate, built
 * on task 363's CandidateAccess in the order every candidate endpoint follows:
 * <ol>
 * <li>requireReader: 401 for the token, session or account; 403 without a CANDIDATES read permission, before any
 * lookup, so an unknown and an existing id answer alike. Not logged.</li>
 * <li>In one REPEATABLE READ transaction (read-write only because a granted read inserts its log rows): a profile
 * without any linked application is unknown, 404 CANDIDATE_NOT_FOUND, not logged.</li>
 * <li>The applications the reader may read, filtered in SQL with CandidateAccess.READABLE. None: the read transaction
 * ends (rolled back), then one DENIED PROFILE row without an application is logged in its own short transaction, and
 * the answer is 403 FORBIDDEN.</li>
 * <li>Otherwise the view is built from those applications only, one GRANTED PROFILE row per returned application is
 * written in the same transaction, and it commits.</li>
 * </ol>
 * Not transactional itself: the read transaction is a TransactionTemplate so the refusal is logged strictly after it,
 * and a request never holds two pool connections.
 */
@Service
public class CandidateProfileService {
    private final CandidateAccess access;
    private final CandidateProfileRepository profiles;
    private final ScreeningRepository screenings;
    private final TransactionTemplate reads;

    CandidateProfileService(CandidateAccess access, CandidateProfileRepository profiles, ScreeningRepository screenings,
                            PlatformTransactionManager transactions) {
        this.access = access;
        this.profiles = profiles;
        this.screenings = screenings;
        this.reads = new TransactionTemplate(transactions);
        // One snapshot for the existence check, the scope (team and department tree) and every row shown.
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public CandidateProfileView profile(Jwt jwt, UUID candidateId) {
        CandidateReader reader = access.requireReader(jwt);
        Read read = Objects.requireNonNull(reads.execute(status -> {
            if (!profiles.hasApplications(candidateId)) {
                status.setRollbackOnly();
                return new Read.Unknown();
            }
            List<ReadableApplication> readable = profiles.readableApplications(reader, candidateId);
            if (readable.isEmpty()) {
                status.setRollbackOnly();
                return new Read.Refused();
            }
            // Only ids already filtered by CandidateAccess.READABLE enter the assessment repository.
            // Same REPEATABLE READ snapshot: no cross-position notes can enter this profile by email linkage.
            Map<UUID, ScreeningView> assessments = new HashMap<>();
            for (ReadableApplication application : readable) {
                assessments.put(application.id(), screenings.view(application.id(), 0, 5));
            }
            CandidateProfileView view = CandidateProfileView.of(candidateId, readable, assessments);
            access.recordGranted(reader, CandidateAccessKind.PROFILE, candidateId,
                    view.applications().stream().map(ApplicationDetail::id).toList());
            return new Read.Shown(view);
        }));
        return switch (read) {
            case Read.Shown shown -> shown.view();
            case Read.Unknown unknown -> throw new ApiException(HttpStatus.NOT_FOUND, "CANDIDATE_NOT_FOUND",
                    "Không tìm thấy hồ sơ ứng viên.");
            case Read.Refused refused -> {
                // The read transaction has ended: the refusal commits alone (CandidateAccess.recordDenied).
                access.recordDenied(reader, CandidateAccessKind.PROFILE, candidateId, null);
                throw new AccessDeniedException("No readable application in this candidate profile");
            }
        };
    }

    // What the read transaction found; the switch above is exhaustive over it.
    private sealed interface Read {
        record Shown(CandidateProfileView view) implements Read { }

        record Unknown() implements Read { }

        record Refused() implements Read { }
    }
}
