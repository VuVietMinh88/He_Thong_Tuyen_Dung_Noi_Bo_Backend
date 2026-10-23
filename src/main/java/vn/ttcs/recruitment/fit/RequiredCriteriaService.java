package vn.ttcs.recruitment.fit;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.auth.AuthService;
import vn.ttcs.recruitment.auth.AuthenticationFailureException;
import vn.ttcs.recruitment.candidate.CandidateLabels;
import vn.ttcs.recruitment.candidate.CandidateWriteGuard;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.security.AccessScope;
import vn.ttcs.recruitment.security.PermissionModule;
import vn.ttcs.recruitment.security.PermissionService;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Task 412 (story S5-09, "Khai báo tiêu chí bắt buộc cho vị trí: số năm kinh nghiệm, bằng cấp, kỹ năng"): the
 * required criteria of a catalog position. Positions belong to the organization catalog, so reading needs
 * ORGANIZATION_READ_ALL and saving ORGANIZATION_WRITE_ALL (ADMIN, HR_MANAGER), both read again from the database.
 * <p>
 * A save locks the caller's account and session, then the position FOR NO KEY UPDATE (404 for an unknown one), checks
 * the permission again, compares expectedVersion with the stored version (409 FIT_CRITERIA_CHANGED) and replaces the
 * criteria with version + 1.
 */
@Service
public class RequiredCriteriaService {
    static final int MAX_SKILLS = 20;
    static final int MAX_SKILL_LENGTH = 60;
    private final RequiredCriteriaRepository criteria;
    private final CandidateWriteGuard guard;
    private final AuthService auth;
    private final PermissionService permissions;
    private final Clock clock;
    private final TransactionTemplate reads;
    private final TransactionTemplate writes;

    RequiredCriteriaService(RequiredCriteriaRepository criteria, CandidateWriteGuard guard, AuthService auth,
                            PermissionService permissions, Clock clock, PlatformTransactionManager transactions) {
        this.criteria = criteria;
        this.guard = guard;
        this.auth = auth;
        this.permissions = permissions;
        this.clock = clock;
        reads = new TransactionTemplate(transactions);
        reads.setReadOnly(true);
        writes = new TransactionTemplate(transactions);
        writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        writes.setTimeout(10);
    }

    public RequiredCriteriaView get(Jwt jwt, UUID positionId) {
        if (jwt == null || jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(clock.instant())) {
            throw AuthenticationFailureException.sessionInvalid();
        }
        Account account = auth.requireActiveAccount(jwt);
        AccessScope.read(permissions.forUser(account.getId()), PermissionModule.ORGANIZATION).orDeny();
        return Objects.requireNonNull(reads.execute(status -> {
            if (!criteria.positionExists(positionId)) throw positionNotFound();
            return RequiredCriteriaView.of(positionId, criteria.find(positionId).orElse(null));
        }));
    }

    public RequiredCriteriaView save(Jwt jwt, UUID positionId, RequiredCriteriaRequest request) {
        List<CandidateLabels.Label> skills = CandidateLabels.labels(request.requiredSkills(), "requiredSkills",
                MAX_SKILLS, MAX_SKILL_LENGTH);
        try {
            return Objects.requireNonNull(writes.execute(status -> {
                CandidateWriteGuard.Writer writer = guard.lockWriter(jwt);
                requireOrganizationWriter(guard.freshGrants(jwt, writer));
                if (!criteria.lockPosition(positionId)) throw positionNotFound();
                requireOrganizationWriter(guard.freshGrants(jwt, writer));
                long version = criteria.lockVersion(positionId);
                if (version != request.expectedVersion()) {
                    throw new ApiException(HttpStatus.CONFLICT, "FIT_CRITERIA_CHANGED",
                            "Tiêu chí của vị trí vừa được thay đổi ở nơi khác. Vui lòng tải lại.");
                }
                criteria.save(positionId, request.minExperienceYears(), request.requiredEducation(), skills, writer.id(),
                        clock.instant().truncatedTo(ChronoUnit.MICROS), version + 1);
                return RequiredCriteriaView.of(positionId, criteria.find(positionId).orElseThrow());
            }));
        } catch (RuntimeException failure) {
            if (CandidateWriteGuard.busy(failure)) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "FIT_CRITERIA_BUSY",
                        "Vị trí đang được xử lý. Vui lòng thử lại.");
            }
            throw failure;
        }
    }

    private static void requireOrganizationWriter(java.util.Set<String> grants) {
        AccessScope.write(grants, PermissionModule.ORGANIZATION).orDeny();
    }

    static ApiException positionNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "POSITION_NOT_FOUND", "Không tìm thấy vị trí tuyển dụng.");
    }
}
