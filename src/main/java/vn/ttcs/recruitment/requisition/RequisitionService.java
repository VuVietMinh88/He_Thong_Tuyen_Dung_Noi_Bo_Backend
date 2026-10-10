package vn.ttcs.recruitment.requisition;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
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
import vn.ttcs.recruitment.auth.AuthSession;
import vn.ttcs.recruitment.auth.AuthSessionRepository;
import vn.ttcs.recruitment.auth.AuthenticationFailureException;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.common.BusinessCalendar;
import vn.ttcs.recruitment.department.DepartmentRepository;
import vn.ttcs.recruitment.headcount.HeadcountDemand;
import vn.ttcs.recruitment.headcount.HeadcountExcess;
import vn.ttcs.recruitment.headcount.HeadcountOverrideView;
import vn.ttcs.recruitment.headcount.HeadcountQuota;
import vn.ttcs.recruitment.position.PositionRepository;
import vn.ttcs.recruitment.position.SalaryBandComparison;
import vn.ttcs.recruitment.position.SalaryBandService;
import vn.ttcs.recruitment.security.AccessScope;
import vn.ttcs.recruitment.security.PermissionModule;
import vn.ttcs.recruitment.security.PermissionService;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class RequisitionService {
    // Newest first; the id only keeps the order stable when two requisitions share the same creation time.
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    // Task 247: the same text is the message and the form error of salaryJustification. It says only that the
    // proposal is outside the standard band, never the band itself: most callers lack SALARY_RANGES_READ_ALL.
    private static final String JUSTIFICATION_REQUIRED_MESSAGE =
            "Dải lương đề xuất nằm ngoài dải lương chuẩn của chức danh, vui lòng nhập giải trình.";

    private final RecruitmentRequisitionRepository requisitions;
    private final PositionRepository positions;
    private final DepartmentRepository departments;
    private final AccountRepository accounts;
    private final AuthSessionRepository sessions;
    private final AuthService auth;
    private final PermissionService permissions;
    private final SalaryBandService salaryBands;
    private final HeadcountQuota headcountQuota;
    private final Validator validator;
    private final BusinessCalendar calendar;
    private final Clock clock;

    public RequisitionService(RecruitmentRequisitionRepository requisitions, PositionRepository positions,
                              DepartmentRepository departments, AccountRepository accounts,
                              AuthSessionRepository sessions, AuthService auth, PermissionService permissions,
                              SalaryBandService salaryBands, HeadcountQuota headcountQuota, Validator validator,
                              BusinessCalendar calendar, Clock clock) {
        this.requisitions = requisitions;
        this.positions = positions;
        this.departments = departments;
        this.accounts = accounts;
        this.sessions = sessions;
        this.auth = auth;
        this.permissions = permissions;
        this.salaryBands = salaryBands;
        this.headcountQuota = headcountQuota;
        this.validator = validator;
        this.calendar = calendar;
        this.clock = clock;
    }

    // Who is calling and how much of the REQUISITIONS module they may see or change (ALL or SCOPED, never NONE).
    // permissions: every code the caller holds, read together with the scope (after the locks for a write).
    private record Caller(UUID id, AccessScope scope, Set<String> permissions) {
        // Task 275: only the HR manager may save a requisition over the headcount plan.
        boolean mayOverrideHeadcountPlan() {
            return permissions.contains("HEADCOUNT_PLANS_WRITE_ALL");
        }

        // Task 275: the salary amounts of a plan are only for those who may read the plans.
        boolean mayReadHeadcountPlans() {
            return permissions.contains("HEADCOUNT_PLANS_READ_ALL");
        }
    }

    // Task 244: saves a new DRAFT owned by the caller, so the manager can come back and finish it later.
    // Task 246 adds: the position and the department must still be active. Task 247 adds: a proposal outside the
    // position's standard salary band needs a justification. Task 248 adds: the needed-by date is not in the past.
    // Task 249 adds: a SCOPED caller may only choose a department they manage.
    // Task 274 adds, last: the draft must fit in the headcount plan of its department and year. Task 275 adds: unless
    // the HR manager confirms going over it with a reason, which is stored with the numbers it was checked against.
    @Transactional
    public RequisitionView create(Jwt jwt, RequisitionRequest request) {
        Writer writer = lockWriter(jwt);
        requireStillWriter(jwt, writer);
        return createDraft(jwt, writer, request);
    }

    /**
     * Task 278: a new draft with the content of an existing requisition the caller may read: position, department,
     * headcount, reason, proposed salary, salary justification, needed-by date, job description and candidate
     * requirements. The copy is a new draft of the caller, checked exactly like POST /requisitions (active position
     * and department, department scope, standard band, headcount plan), so a copy can never be a draft that create
     * would refuse. headcountOverrideReason plays the same role as in the create body.
     * Checks in order: write access (401/403), read access to requisitions at all (403), the source exists (404),
     * the source's department is in the read scope (403), then the checks of create. The read permission comes before
     * the lookup, so a caller who may only write cannot learn which ids exist. The source is only read, never locked
     * or changed.
     */
    @Transactional
    public RequisitionView copy(Jwt jwt, UUID sourceId, String headcountOverrideReason) {
        Writer writer = lockWriter(jwt);
        Caller caller = requireStillWriter(jwt, writer);
        Caller reader = asReader(caller);
        var source = requisitions.findById(sourceId).orElseThrow(RequisitionService::notFound);
        requireInScope(reader, source.getDepartmentId());
        var request = new RequisitionRequest(source.getPositionId(), source.getDepartmentId(),
                source.getHeadcount(), source.getReason().name(), source.getProposedSalaryMin(),
                source.getProposedSalaryMax(), source.getSalaryJustification(), source.getNeededBy(),
                source.getJobDescription(), source.getCandidateRequirements(), headcountOverrideReason);
        requireValidCopy(request);
        return createDraft(jwt, writer, request);
    }

    // The copied content did not come through @Valid. A row saved by the API always passes; one written by hand, or
    // saved before a limit was lowered, is reported like an invalid create body (400 VALIDATION_ERROR) and not copied.
    private void requireValidCopy(RequisitionRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (ConstraintViolation<RequisitionRequest> violation : validator.validate(request)) {
            errors.putIfAbsent(violation.getPropertyPath().toString(), violation.getMessage());
        }
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "Yêu cầu gốc có dữ liệu không còn hợp lệ nên không sao chép được.", errors);
        }
    }

    // The checks and the save shared by create and copy, after the first check of the caller (right after lockWriter).
    // The row locks below can wait, so the caller is checked again after them (see create).
    private RequisitionView createDraft(Jwt jwt, Writer writer, RequisitionRequest request) {
        requireValidSalaryRange(request);
        requireNeededByNotInPast(request);
        requireActivePositionAndDepartment(request);
        // The FOR SHARE locks above can wait for HR's edit of the position or the department, and meanwhile the
        // access token may expire or the permission may be removed: check the caller again before saving.
        Caller caller = requireStillWriter(jwt, writer);
        requireChosenDepartmentInScope(caller, request);
        requireJustificationOutsideStandardBand(request);
        Instant createdAt = now();
        var confirmed = requireWithinHeadcountPlan(jwt, writer, request, demand(request, createdAt), null, null);
        var requisition = requisitions.saveAndFlush(new RecruitmentRequisition(request.positionId(),
                request.departmentId(), request.headcount(), request.reasonCode(), request.proposedSalaryMin(),
                request.proposedSalaryMax(), request.salaryJustification(), request.neededBy(),
                request.jobDescription(), request.candidateRequirements(), caller.id(), createdAt));
        recordHeadcountOverride(confirmed, requisition.getId(), request, caller, createdAt);
        return RequisitionView.from(requisition);
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
        requireInScope(caller, requisition.getDepartmentId());
        return RequisitionView.from(requisition);
    }

    // Task 245: saves the draft again with the new content. Order of checks: may I change this requisition
    // (404, 403, still a draft), then is the new content valid (same checks as create, including the department
    // scope of task 249 for the department in the body), then (task 274) does it still fit in the headcount plan.
    @Transactional
    public RequisitionView update(Jwt jwt, UUID id, RequisitionRequest request) {
        Writer writer = lockWriter(jwt);
        requireStillWriter(jwt, writer);
        // Lock order: the actor's account, the actor's session (inside lockWriter), then the requisition,
        // then (shared) the chosen position and department.
        var locked = requisitions.findByIdForUpdate(id);
        // Every row lock can wait, and meanwhile the access token may expire or the permission may be removed, so the
        // caller is checked again after each of them: here before the 404 (like DepartmentService.lockDepartment,
        // task 198) and again after the position and department locks, right before the save.
        Caller caller = requireStillWriter(jwt, writer);
        var requisition = locked.orElseThrow(RequisitionService::notFound);
        // The scope is checked after this lock, so a department manager change made while we waited is respected.
        requireInScope(caller, requisition.getDepartmentId());
        requireDraft(requisition);
        requireValidSalaryRange(request);
        requireNeededByNotInPast(request);
        requireActivePositionAndDepartment(request);
        caller = requireStillWriter(jwt, writer);
        requireChosenDepartmentInScope(caller, request);
        requireJustificationOutsideStandardBand(request);
        var confirmed = requireWithinHeadcountPlan(jwt, writer, request, demand(request, requisition.getCreatedAt()),
                demand(requisition), id);
        Instant updatedAt = now();
        requisition.updateDraft(request.positionId(), request.departmentId(), request.headcount(),
                request.reasonCode(), request.proposedSalaryMin(), request.proposedSalaryMax(),
                request.salaryJustification(), request.neededBy(), request.jobDescription(),
                request.candidateRequirements(), updatedAt);
        requisitions.flush();
        recordHeadcountOverride(confirmed, id, request, caller, updatedAt);
        return RequisitionView.from(requisition);
    }

    // Task 275: the HR exceptions to the headcount plan recorded for this requisition, newest first. Same access as
    // GET /requisitions/{id}; only callers who may read the plans (HEADCOUNT_PLANS_READ_ALL) see the salary amounts.
    // An exception keeps the department of the plan it went over, and the requisition may have moved since: a SCOPED
    // caller only sees the exceptions of departments they manage, never the plan numbers of another department.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public HeadcountOverrideList headcountOverrides(Jwt jwt, UUID id) {
        Caller caller = requireReadAccess(jwt);
        var requisition = requisitions.findById(id).orElseThrow(RequisitionService::notFound);
        requireInScope(caller, requisition.getDepartmentId());
        boolean showSalary = caller.mayReadHeadcountPlans();
        var overrides = headcountQuota.overridesOf(id).stream();
        if (caller.scope() != AccessScope.ALL) {
            Set<UUID> managed = departments.findManagedDepartmentIds(caller.id());
            overrides = overrides.filter(override -> managed.contains(override.departmentId()));
        }
        return new HeadcountOverrideList(overrides
                .map(override -> HeadcountOverrideView.from(override, showSalary)).toList());
    }

    private Caller requireReadAccess(Jwt jwt) {
        requireUnexpiredToken(jwt, clock.instant());
        Account actor = auth.requireActiveAccount(jwt);
        // NONE throws AccessDeniedException, which the security layer turns into the standard 403 FORBIDDEN.
        Set<String> granted = permissions.forUser(actor.getId());
        AccessScope scope = AccessScope.read(granted, PermissionModule.REQUISITIONS).orDeny();
        return new Caller(actor.getId(), scope, granted);
    }

    // The caller of a write after lockWriter: the account id and the session row this transaction has locked.
    private record Writer(UUID actorId, AuthSession session) { }

    private Writer lockWriter(Jwt jwt) {
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

        return new Writer(actorId, session);
    }

    // May the caller still write? Called once the account and session locks are held (the request may have waited
    // for them), and again after every row lock the write waits for. From the first call on this transaction holds
    // the caller's account and session rows, so an admin lock, a logout or a role change through the account API
    // waits for it. Time still passes (token or session expiry) and the grants in role_permissions can still change
    // (a migration locks no account), so all three are checked every time, with the permissions read again from the
    // database.
    private Caller requireStillWriter(Jwt jwt, Writer writer) {
        var now = clock.instant();
        requireUnexpiredToken(jwt, now);
        if (!writer.session().getUserId().equals(writer.actorId()) || !writer.session().isActive(now)) {
            throw AuthenticationFailureException.sessionInvalid();
        }
        // ALL (ADMIN, HR_MANAGER) and SCOPED (HIRING_MANAGER, RECRUITER, APPROVER) may both write.
        // NONE throws AccessDeniedException, which the security layer turns into the standard 403 FORBIDDEN.
        // SCOPED callers are then limited to their departments: the saved requisition on update (requireInScope)
        // and the department written in the body on create and update (requireChosenDepartmentInScope).
        Set<String> granted = permissions.forUser(writer.actorId());
        AccessScope scope = AccessScope.write(granted, PermissionModule.REQUISITIONS).orDeny();
        return new Caller(writer.actorId(), scope, granted);
    }

    // Task 278: copying shows the source's content, so the writer must also be allowed to read it. This is the same
    // caller with its READ scope of REQUISITIONS, which a writer may lack (WRITE never implies READ: 403 here) or have
    // with another scope (READ_ALL with WRITE_SCOPED, for example). Permissions were read after the account lock.
    private static Caller asReader(Caller writer) {
        AccessScope readScope = AccessScope.read(writer.permissions(), PermissionModule.REQUISITIONS).orDeny();
        return new Caller(writer.id(), readScope, writer.permissions());
    }

    // ALL reaches every department. SCOPED only reaches a department the caller manages, directly or through a
    // parent department (departments.manager_user_id). Used for the department of a saved requisition (get, update)
    // and for the department chosen in the body (create, update). Who created a requisition does not matter: when a
    // department gets a new manager, its drafts move with it. Out of scope is the standard 403 FORBIDDEN (house rule
    // in docs/architecture/authorization.md), not a 404.
    private void requireInScope(Caller caller, UUID departmentId) {
        if (caller.scope() == AccessScope.ALL) {
            return;
        }
        if (!departments.findManagedDepartmentIds(caller.id()).contains(departmentId)) {
            throw new AccessDeniedException("Department is outside the caller's requisition scope");
        }
    }

    // Task 249: a department head (SCOPED) writes requisitions only for the departments they manage, including
    // every department below them: the head of IT may choose IT, IT_DEV or IT_QA, but not SALES or a parent of IT.
    // On update this also stops moving a draft out of the caller's departments. ALL callers choose any department.
    // Called after requireActivePositionAndDepartment, on purpose:
    // - an unknown department is still the 400 INVALID_REQUISITION_DEPARTMENT form error, like 404 before 403;
    // - the chosen department row is locked FOR SHARE, so HR cannot give it another manager or parent before this
    //   transaction commits, and a change HR committed while this request waited for that lock is seen here.
    //   A change to a parent department is not blocked: if it commits after this check, it counts as made after
    //   this save, like any change made a moment later.
    private void requireChosenDepartmentInScope(Caller caller, RequisitionRequest request) {
        requireInScope(caller, request.departmentId());
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

    // Task 248: the people cannot be needed before today. "Today" is the date in the business time zone
    // (BusinessCalendar, Vietnam by default), not in UTC: at 2026-10-06T17:30Z it is already 2026-10-07 in Vietnam,
    // so 2026-10-06 is refused although UTC is still on that day. Today itself is allowed, and a draft may leave the
    // date empty. The date is compared on every save, after the account and session locks (and the requisition lock
    // for an update), so a request that waited for those locks past midnight is compared with the new day. It runs
    // before the FOR SHARE locks on the position and department, because NEEDED_BY_IN_PAST is reported before their
    // errors on purpose. So a wait on those two locks past midnight, or midnight passing before the commit, can still
    // save a date that is one day in the past at commit time. A draft whose date has passed is saved again only with
    // a new date or none.
    private void requireNeededByNotInPast(RequisitionRequest request) {
        LocalDate neededBy = request.neededBy();
        if (neededBy != null && neededBy.isBefore(calendar.today())) {
            throw invalidField("NEEDED_BY_IN_PAST", "neededBy", "Ngày cần người không được trước ngày hôm nay.");
        }
    }

    // The position and the department must exist: V13 foreign keys would reject an unknown id with a 500, so it is
    // reported as a form error first. Task 246: they must also still be active. HR sets active=false to stop new
    // hiring for a position or department, so a draft cannot choose one; a draft whose position or department was
    // deactivated later is saved again only after the manager picks an active one (or HR turns it back on).
    // Both rows are read with FOR SHARE (see the repositories): a deactivation waits until this transaction commits,
    // and one committed while this request waited is seen here. There is no API that deletes positions. Deleting a
    // department (task 197) locks its row FOR UPDATE: it waits for this transaction and then refuses because of the
    // saved requisition; a delete that committed while this request waited makes the department "not found" here.
    private void requireActivePositionAndDepartment(RequisitionRequest request) {
        Optional<Boolean> positionActive = positions.findActiveForShare(request.positionId());
        if (positionActive.isEmpty()) {
            throw invalidField("INVALID_REQUISITION_POSITION", "positionId", "Chức danh không tồn tại.");
        }
        if (!positionActive.get()) {
            throw invalidField("REQUISITION_POSITION_INACTIVE", "positionId",
                    "Chức danh đã ngừng áp dụng, hãy chọn chức danh khác.");
        }
        Optional<Boolean> departmentActive = departments.findActiveForShare(request.departmentId());
        if (departmentActive.isEmpty()) {
            throw invalidField("INVALID_REQUISITION_DEPARTMENT", "departmentId", "Phòng ban không tồn tại.");
        }
        if (!departmentActive.get()) {
            throw invalidField("REQUISITION_DEPARTMENT_INACTIVE", "departmentId",
                    "Phòng ban đã ngừng áp dụng, hãy chọn phòng ban khác.");
        }
    }

    // Task 247: a proposed salary outside the standard band of the chosen position must be explained. The band
    // edges themselves count as inside (SalaryBandComparison.WITHIN). A draft may still give only one end of the
    // proposal, so each end that is filled in is compared on its own; no proposal at all needs no justification.
    // RequisitionRequest has already turned a blank justification into null, so "   " counts as missing.
    // A justification that is written is always kept, even when the proposal is inside the band.
    // Called after requireActivePositionAndDepartment: the position exists, is active and is locked FOR SHARE, so
    // SalaryBandService never answers 404/409 here, and HR cannot change the band before this transaction commits.
    // The band is compared on every save with its current value: a draft saved earlier may need a justification
    // when it is saved again after HR changed the band.
    private void requireJustificationOutsideStandardBand(RequisitionRequest request) {
        if (request.salaryJustification() != null) {
            return;
        }
        if (isOutsideStandardBand(request.positionId(), request.proposedSalaryMin())
                || isOutsideStandardBand(request.positionId(), request.proposedSalaryMax())) {
            throw invalidField("SALARY_JUSTIFICATION_REQUIRED", "salaryJustification", JUSTIFICATION_REQUIRED_MESSAGE);
        }
    }

    // Task 274: a save that asks more of the plan than it has left is refused with 409 (HeadcountQuota). Runs last,
    // after every 400 and 403 of this save, and after the department row is locked FOR SHARE: the plan is then locked
    // FOR UPDATE until this transaction commits, so parallel saves for the same department and year are counted one
    // after the other. Without a plan for that department and year nothing is checked and nothing is locked.
    // That plan lock can wait for another save of the same department and year, and meanwhile the access token may
    // expire or the permission may be removed, so the caller is checked again before the 409 is decided.
    // Task 275: the save goes through anyway when it carries headcountOverrideReason and the caller may override
    // (HEADCOUNT_PLANS_WRITE_ALL, read again after the plan lock); anyone else giving a reason gets 403. The excess is
    // returned so that it can be recorded once the requisition row exists. A reason on a save that fits the plan is
    // not needed and is ignored.
    private Optional<HeadcountExcess> requireWithinHeadcountPlan(Jwt jwt, Writer writer, RequisitionRequest request,
                                                                 HeadcountDemand demand, HeadcountDemand previous,
                                                                 UUID requisitionId) {
        Optional<HeadcountExcess> excess = headcountQuota.check(demand, previous, requisitionId);
        Caller current = requireStillWriter(jwt, writer);
        if (excess.isEmpty()) {
            return excess;
        }
        if (request.headcountOverrideReason() == null) {
            throw HeadcountQuota.exceeded(excess.get());
        }
        if (!current.mayOverrideHeadcountPlan()) {
            throw new AccessDeniedException("Only HEADCOUNT_PLANS_WRITE_ALL may save a requisition over the plan");
        }
        return excess;
    }

    // Runs after the requisition row is written (the exception references it), still under the plan lock.
    private void recordHeadcountOverride(Optional<HeadcountExcess> confirmed, UUID requisitionId,
                                         RequisitionRequest request, Caller caller, Instant at) {
        confirmed.ifPresent(excess -> headcountQuota.recordOverride(excess, requisitionId,
                request.headcountOverrideReason(), caller.id(), at));
    }

    // What the body asks of the plan. createdAt decides the plan year while neededBy is empty.
    private HeadcountDemand demand(RequisitionRequest request, Instant createdAt) {
        return HeadcountDemand.of(request.departmentId(), request.neededBy(), calendar.dateOf(createdAt),
                request.headcount(), request.proposedSalaryMin(), request.proposedSalaryMax());
    }

    // What the saved requisition asks of its plan before this save.
    private HeadcountDemand demand(RecruitmentRequisition requisition) {
        return HeadcountDemand.of(requisition.getDepartmentId(), requisition.getNeededBy(),
                calendar.dateOf(requisition.getCreatedAt()), requisition.getHeadcount(),
                requisition.getProposedSalaryMin(), requisition.getProposedSalaryMax());
    }

    // compare() returns only BELOW/WITHIN/ABOVE, so this service never handles the band amounts at all.
    private boolean isOutsideStandardBand(UUID positionId, Long proposedSalary) {
        return proposedSalary != null && salaryBands.compare(positionId, proposedSalary) != SalaryBandComparison.WITHIN;
    }

    // A 400 about one request field: the same text is the message and the form error of that field.
    private static ApiException invalidField(String code, String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message, Map.of(field, message));
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
