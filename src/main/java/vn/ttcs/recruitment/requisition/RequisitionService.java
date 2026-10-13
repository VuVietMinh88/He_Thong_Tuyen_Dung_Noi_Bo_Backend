package vn.ttcs.recruitment.requisition;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.auth.AuthSessionRepository;
import vn.ttcs.recruitment.auth.AuthenticationFailureException;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.department.DepartmentRepository;
import vn.ttcs.recruitment.position.PositionRepository;
import vn.ttcs.recruitment.security.AccessScope;
import vn.ttcs.recruitment.security.PermissionModule;
import vn.ttcs.recruitment.security.PermissionService;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

@Service
public class RequisitionService {
    private final RecruitmentRequisitionRepository requisitions;
    private final PositionRepository positions;
    private final DepartmentRepository departments;
    private final AccountRepository accounts;
    private final AuthSessionRepository sessions;
    private final PermissionService permissions;
    private final Clock clock;

    public RequisitionService(RecruitmentRequisitionRepository requisitions, PositionRepository positions,
                              DepartmentRepository departments, AccountRepository accounts,
                              AuthSessionRepository sessions, PermissionService permissions, Clock clock) {
        this.requisitions = requisitions;
        this.positions = positions;
        this.departments = departments;
        this.accounts = accounts;
        this.sessions = sessions;
        this.permissions = permissions;
        this.clock = clock;
    }

    // Task 244: saves a new DRAFT owned by the caller, so the manager can come back and finish it later.
    // Business rules on the content (salary justification, needed-by date, department scope) are not checked yet;
    // the later tasks of story S2-10 add them here.
    @Transactional
    public RequisitionView create(Jwt jwt, RequisitionRequest request) {
        UUID actorId = requireWriteAccess(jwt);
        requireValidSalaryRange(request);
        requireExistingPositionAndDepartment(request);
        var requisition = new RecruitmentRequisition(request.positionId(), request.departmentId(),
                request.headcount(), request.reason(), request.proposedSalaryMin(), request.proposedSalaryMax(),
                request.salaryJustification(), request.neededBy(), request.jobDescription(),
                request.candidateRequirements(), actorId, now());
        return RequisitionView.from(requisitions.saveAndFlush(requisition));
    }

    // Returns the caller's account id, which becomes created_by of the new requisition.
    private UUID requireWriteAccess(Jwt jwt) {
        UUID actorId;
        UUID sessionId;
        try {
            actorId = UUID.fromString(jwt.getSubject());
            sessionId = UUID.fromString(jwt.getId());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw AuthenticationFailureException.sessionInvalid();
        }
        // Same lock order as the other write services: the actor's account first, then the actor's session.
        // Role, lock and logout changes wait for these locks, so they cannot interleave with this write.
        accounts.findByIdForUpdate(actorId).filter(Account::isAccessAllowed)
                .orElseThrow(AuthenticationFailureException::sessionInvalid);
        var session = sessions.findByIdForUpdate(sessionId)
                .orElseThrow(AuthenticationFailureException::sessionInvalid);

        // The request may have waited for those locks. Recheck the token, session and permission now.
        var now = clock.instant();
        if (jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(now)) {
            throw AuthenticationFailureException.sessionInvalid();
        }
        if (!session.getUserId().equals(actorId) || !session.isActive(now)) {
            throw AuthenticationFailureException.sessionInvalid();
        }
        // ALL (ADMIN, HR_MANAGER) and SCOPED (HIRING_MANAGER, RECRUITER, APPROVER) may both create a draft.
        // NONE throws AccessDeniedException, which the security layer turns into the standard 403 FORBIDDEN.
        // The SCOPED limit "only departments the caller manages" is not applied yet (task 249).
        AccessScope.write(permissions.forUser(actorId), PermissionModule.REQUISITIONS).orDeny();
        return actorId;
    }

    // RequisitionRequest has already checked each salary on its own. A draft may leave one or both ends empty;
    // when both are present, V13 requires min <= max. Checking here returns a form error on proposedSalaryMax
    // instead of letting the database CHECK fail as a 500.
    private static void requireValidSalaryRange(RequisitionRequest request) {
        Long min = request.proposedSalaryMin();
        Long max = request.proposedSalaryMax();
        if (min != null && max != null && min > max) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REQUISITION_SALARY_RANGE_INVALID",
                    "Lương đề xuất tối thiểu không được lớn hơn lương đề xuất tối đa.",
                    Map.of("proposedSalaryMax", "Lương đề xuất tối đa phải lớn hơn hoặc bằng lương đề xuất tối thiểu."));
        }
    }

    // V13 foreign keys would reject an unknown id with a 500, so report it as a form error first.
    // There is no API that deletes positions or departments, so the ids cannot disappear before the insert.
    private void requireExistingPositionAndDepartment(RequisitionRequest request) {
        if (!positions.existsById(request.positionId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUISITION_POSITION", "Chức danh không tồn tại.",
                    Map.of("positionId", "Chức danh không tồn tại."));
        }
        if (departments.findById(request.departmentId()).isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUISITION_DEPARTMENT", "Phòng ban không tồn tại.",
                    Map.of("departmentId", "Phòng ban không tồn tại."));
        }
    }

    // PostgreSQL TIMESTAMPTZ keeps microseconds, so the response shows the same time a later read returns.
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
