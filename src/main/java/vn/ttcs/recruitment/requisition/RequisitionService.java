package vn.ttcs.recruitment.requisition;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.auth.AuthService;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class RequisitionService {
    // Newest first; the id only keeps the order stable when two requisitions share the same creation time.
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final RecruitmentRequisitionRepository requisitions;
    private final PositionRepository positions;
    private final DepartmentRepository departments;
    private final AccountRepository accounts;
    private final AuthSessionRepository sessions;
    private final AuthService auth;
    private final PermissionService permissions;
    private final Clock clock;

    public RequisitionService(RecruitmentRequisitionRepository requisitions, PositionRepository positions,
                              DepartmentRepository departments, AccountRepository accounts,
                              AuthSessionRepository sessions, AuthService auth, PermissionService permissions,
                              Clock clock) {
        this.requisitions = requisitions;
        this.positions = positions;
        this.departments = departments;
        this.accounts = accounts;
        this.sessions = sessions;
        this.auth = auth;
        this.permissions = permissions;
        this.clock = clock;
    }

    // Who is calling and how much of the REQUISITIONS module they may see or change (ALL or SCOPED, never NONE).
    private record Caller(UUID id, AccessScope scope) { }

    // Task 244: saves a new DRAFT owned by the caller, so the manager can come back and finish it later.
    // Business rules on the content (salary justification, needed-by date, department scope) are not checked yet;
    // the later tasks of story S2-10 add them here.
    @Transactional
    public RequisitionView create(Jwt jwt, RequisitionRequest request) {
        Caller caller = requireWriteAccess(jwt);
        requireValidSalaryRange(request);
        requireExistingPositionAndDepartment(request);
        var requisition = new RecruitmentRequisition(request.positionId(), request.departmentId(),
                request.headcount(), request.reason(), request.proposedSalaryMin(), request.proposedSalaryMax(),
                request.salaryJustification(), request.neededBy(), request.jobDescription(),
                request.candidateRequirements(), caller.id(), now());
        return RequisitionView.from(requisitions.saveAndFlush(requisition));
    }

    // Task 245: one page of the requisitions the caller may see, newest first. status = null means every status.
    // REPEATABLE_READ: the managed departments, the count and the page all come from the same snapshot.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RequisitionPage list(Jwt jwt, RequisitionStatus status, int page, int size) {
        Caller caller = requireReadAccess(jwt);
        // Spring Data JPA turns page * size into an int OFFSET and fails with a 500 above Integer.MAX_VALUE,
        // so such a page is rejected here as a normal 400. The long multiplication itself cannot overflow.
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "Trang hoặc số lượng yêu cầu tuyển dụng không hợp lệ.");
        }
        List<RequisitionStatus> statuses = status == null ? List.of(RequisitionStatus.values()) : List.of(status);
        var pageable = PageRequest.of(page, size, NEWEST_FIRST);
        Page<RecruitmentRequisition> result;
        if (caller.scope() == AccessScope.ALL) {
            result = requisitions.findByStatusIn(statuses, pageable);
        } else {
            // SCOPED: the department filter is part of the SQL query, so other departments never leave the database.
            Set<UUID> managed = departments.findManagedDepartmentIds(caller.id());
            if (managed.isEmpty()) {
                return new RequisitionPage(List.of(), page, size, 0, 0);
            }
            result = requisitions.findByStatusInAndDepartmentIdIn(statuses, managed, pageable);
        }
        return new RequisitionPage(result.getContent().stream().map(RequisitionView::from).toList(),
                page, size, result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RequisitionView get(Jwt jwt, UUID id) {
        Caller caller = requireReadAccess(jwt);
        var requisition = requisitions.findById(id).orElseThrow(RequisitionService::notFound);
        requireInScope(caller, requisition);
        return RequisitionView.from(requisition);
    }

    // Task 245: saves the draft again with the new content. Order of checks: may I change this requisition
    // (404, 403, still a draft), then is the new content valid (same checks as create).
    @Transactional
    public RequisitionView update(Jwt jwt, UUID id, RequisitionRequest request) {
        Caller caller = requireWriteAccess(jwt);
        // Lock order: the actor's account, the actor's session (inside requireWriteAccess), then the requisition.
        // The scope is checked after this lock, so a department manager change made while we waited is respected.
        var requisition = requisitions.findByIdForUpdate(id).orElseThrow(RequisitionService::notFound);
        requireInScope(caller, requisition);
        requireDraft(requisition);
        requireValidSalaryRange(request);
        requireExistingPositionAndDepartment(request);
        requisition.updateDraft(request.positionId(), request.departmentId(), request.headcount(), request.reason(),
                request.proposedSalaryMin(), request.proposedSalaryMax(), request.salaryJustification(),
                request.neededBy(), request.jobDescription(), request.candidateRequirements(), now());
        requisitions.flush();
        return RequisitionView.from(requisition);
    }

    private Caller requireReadAccess(Jwt jwt) {
        requireUnexpiredToken(jwt, clock.instant());
        Account actor = auth.requireActiveAccount(jwt);
        // NONE throws AccessDeniedException, which the security layer turns into the standard 403 FORBIDDEN.
        AccessScope scope = AccessScope.read(permissions.forUser(actor.getId()), PermissionModule.REQUISITIONS)
                .orDeny();
        return new Caller(actor.getId(), scope);
    }

    private Caller requireWriteAccess(Jwt jwt) {
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
        requireUnexpiredToken(jwt, now);
        if (!session.getUserId().equals(actorId) || !session.isActive(now)) {
            throw AuthenticationFailureException.sessionInvalid();
        }
        // ALL (ADMIN, HR_MANAGER) and SCOPED (HIRING_MANAGER, RECRUITER, APPROVER) may both write.
        // NONE throws AccessDeniedException, which the security layer turns into the standard 403 FORBIDDEN.
        // update() limits SCOPED callers to requisitions of their departments (requireInScope). The department
        // written in the body (create, or moving a draft on update) is not limited yet: that is task 249.
        AccessScope scope = AccessScope.write(permissions.forUser(actorId), PermissionModule.REQUISITIONS).orDeny();
        return new Caller(actorId, scope);
    }

    // ALL reaches every requisition. SCOPED only reaches requisitions of a department the caller manages,
    // directly or through a parent department (departments.manager_user_id). Who created the requisition does not
    // matter: when a department gets a new manager, its drafts move with it. Out of scope is the standard
    // 403 FORBIDDEN (house rule in docs/architecture/authorization.md), not a 404.
    private void requireInScope(Caller caller, RecruitmentRequisition requisition) {
        if (caller.scope() == AccessScope.ALL) {
            return;
        }
        if (!departments.findManagedDepartmentIds(caller.id()).contains(requisition.getDepartmentId())) {
            throw new AccessDeniedException("Requisition belongs to a department outside the caller's scope");
        }
    }

    // Only a draft can be edited. V13 only allows DRAFT today, so this guard matters once the approval workflow
    // adds new statuses: a submitted requisition then changes only through that workflow.
    private static void requireDraft(RecruitmentRequisition requisition) {
        if (requisition.getStatus() != RequisitionStatus.DRAFT) {
            throw new ApiException(HttpStatus.CONFLICT, "REQUISITION_NOT_DRAFT",
                    "Chỉ sửa được yêu cầu tuyển dụng đang ở trạng thái nháp.");
        }
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

    private void requireUnexpiredToken(Jwt jwt, Instant now) {
        if (jwt == null || jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(now)) {
            throw AuthenticationFailureException.sessionInvalid();
        }
    }

    // PostgreSQL TIMESTAMPTZ keeps microseconds, so the response shows the same time a later read returns.
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "REQUISITION_NOT_FOUND", "Không tìm thấy yêu cầu tuyển dụng.");
    }
}
