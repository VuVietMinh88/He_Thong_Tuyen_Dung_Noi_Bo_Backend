package vn.ttcs.recruitment.headcount;

import org.springframework.dao.DuplicateKeyException;
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
import vn.ttcs.recruitment.security.AccessScope;
import vn.ttcs.recruitment.security.PermissionModule;
import vn.ttcs.recruitment.security.PermissionService;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

// Task 273: HR declares and reads the headcount plans; the requisition form reads what is left of one plan.
@Service
public class HeadcountPlanService {
    private static final String READ_ALL = "HEADCOUNT_PLANS_READ_ALL";
    private static final String WRITE_ALL = "HEADCOUNT_PLANS_WRITE_ALL";

    private final HeadcountPlanRepository plans;
    private final DepartmentRepository departments;
    private final AccountRepository accounts;
    private final AuthSessionRepository sessions;
    private final AuthService auth;
    private final PermissionService permissions;
    private final BusinessCalendar calendar;
    private final Clock clock;

    public HeadcountPlanService(HeadcountPlanRepository plans, DepartmentRepository departments,
                                AccountRepository accounts, AuthSessionRepository sessions, AuthService auth,
                                PermissionService permissions, BusinessCalendar calendar, Clock clock) {
        this.plans = plans;
        this.departments = departments;
        this.accounts = accounts;
        this.sessions = sessions;
        this.auth = auth;
        this.permissions = permissions;
        this.calendar = calendar;
        this.clock = clock;
    }

    // One page of plans, newest year first. year and departmentId are optional filters.
    // REPEATABLE_READ: the page, the total and the usage of every plan come from the same snapshot.
    // The usage is summed per plan (at most 100 small queries on the department index), which keeps its rules in
    // one place (HeadcountPlanRepository.usage).
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public HeadcountPlanView.Page list(Jwt jwt, Integer year, UUID departmentId, int page, int size) {
        requireReader(jwt);
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE
                || (year != null && !validYear(year))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "Trang, số lượng hoặc năm kế hoạch không hợp lệ.");
        }
        var result = plans.search(year, departmentId, page, size);
        var items = result.items().stream().map(this::view).toList();
        return new HeadcountPlanView.Page(items, page, size, result.total(), (result.total() + size - 1) / size);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public HeadcountPlanView get(Jwt jwt, UUID id) {
        requireReader(jwt);
        return view(plans.findWithDepartment(id).orElseThrow(HeadcountPlanService::notFound));
    }

    // Checks in order: write access (401/403), the request (400 from @Valid), the department exists (400) and is
    // still active (400), no plan yet for this department and year (409).
    // Lock order: the actor's account, the actor's session, then the department, the same order as a requisition save,
    // which then locks the plan (task 274). The department is locked FOR NO KEY UPDATE, not FOR SHARE: requisition
    // saves of the department hold it FOR SHARE, so a new plan waits for the saves in flight and later saves wait for
    // the plan (see DepartmentRepository.findActiveForNoKeyUpdate). Deleting the department waits for this transaction.
    // Every row lock can wait (a department edit holds it FOR UPDATE), and meanwhile the access token may expire or the
    // permission may be removed, so the caller is checked again after it, right before the insert.
    @Transactional
    public HeadcountPlanView create(Jwt jwt, HeadcountPlanRequest request) {
        Writer writer = lockWriter(jwt);
        requireStillWriter(jwt, writer);
        Optional<Boolean> active = departments.findActiveForNoKeyUpdate(request.departmentId());
        requireStillWriter(jwt, writer);
        if (active.isEmpty()) {
            throw invalidField("INVALID_HEADCOUNT_PLAN_DEPARTMENT", "departmentId", "Phòng ban không tồn tại.");
        }
        if (!active.get()) {
            throw invalidField("HEADCOUNT_PLAN_DEPARTMENT_INACTIVE", "departmentId",
                    "Phòng ban đã ngừng áp dụng nên không khai báo được định biên mới.");
        }
        if (plans.findByDepartmentAndYear(request.departmentId(), request.year()).isPresent()) {
            throw alreadyExists();
        }
        Instant now = now();
        var plan = new HeadcountPlan(UUID.randomUUID(), request.departmentId(), request.year(),
                request.headcountLimit(), request.salaryBudget(), now, now, writer.actorId());
        try {
            plans.insert(plan);
        } catch (DuplicateKeyException exception) {
            // The check above misses a plan another HR request inserted at the same time; the unique key does not.
            throw alreadyExists();
        }
        return view(plans.findWithDepartment(plan.id()).orElseThrow());
    }

    // Replaces the limit and the budget. A plan may become lower than what is already used: requisitions already
    // saved stay, and the remaining numbers turn negative; only new or larger requisitions are then blocked (274).
    // Lock order: the actor's account, the actor's session, then the plan. The department is not locked here, so a
    // requisition save (department FOR SHARE, then this plan FOR UPDATE) and this update cannot wait for each other.
    // The plan lock can wait for a requisition save of that department and year, so the caller is checked again after
    // it, before the 404 (like the other write services).
    @Transactional
    public HeadcountPlanView update(Jwt jwt, UUID id, HeadcountPlanUpdateRequest request) {
        Writer writer = lockWriter(jwt);
        requireStillWriter(jwt, writer);
        var locked = plans.findByIdForUpdate(id);
        requireStillWriter(jwt, writer);
        locked.orElseThrow(HeadcountPlanService::notFound);
        plans.update(id, request.headcountLimit(), request.salaryBudget(), now(), writer.actorId());
        return view(plans.findWithDepartment(id).orElseThrow());
    }

    /**
     * What is left of one department's plan for one year (default: the current business year), for the requisition
     * form. Allowed for HR (HEADCOUNT_PLANS_READ_ALL) and for anyone who may read requisitions of that department:
     * REQUISITIONS_READ_ALL for every department, REQUISITIONS_READ_SCOPED only for the departments the caller
     * manages, including sub-departments, like the requisitions themselves. Only HR sees the salary budget.
     * Checks in order: access (401/403), the parameters (400), the department exists (404), the scope (403).
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public HeadcountRemainingView remaining(Jwt jwt, UUID departmentId, Integer year) {
        requireUnexpiredToken(jwt, clock.instant());
        Account actor = auth.requireActiveAccount(jwt);
        Set<String> granted = permissions.forUser(actor.getId());
        boolean hr = granted.contains(READ_ALL);
        AccessScope requisitionScope = AccessScope.read(granted, PermissionModule.REQUISITIONS);
        if (!hr && requisitionScope == AccessScope.NONE) {
            throw new AccessDeniedException("Headcount remaining requires HEADCOUNT_PLANS_READ_ALL or REQUISITIONS_READ");
        }
        int planYear = year == null ? calendar.today().getYear() : year;
        if (departmentId == null || !validYear(planYear)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "Cần chọn phòng ban, và năm kế hoạch phải từ 2000 đến 2100.");
        }
        if (departments.findById(departmentId).isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "DEPARTMENT_NOT_FOUND", "Không tìm thấy phòng ban.");
        }
        if (!hr && requisitionScope == AccessScope.SCOPED
                && !departments.findManagedDepartmentIds(actor.getId()).contains(departmentId)) {
            throw new AccessDeniedException("Department is outside the caller's requisition scope");
        }
        HeadcountPlan plan = plans.findByDepartmentAndYear(departmentId, planYear).orElse(null);
        return HeadcountRemainingView.of(departmentId, planYear, plan, plans.usage(departmentId, planYear, null), hr);
    }

    private HeadcountPlanView view(HeadcountPlanRepository.PlanWithDepartment row) {
        HeadcountPlan plan = row.plan();
        return HeadcountPlanView.from(row, plans.usage(plan.departmentId(), plan.year(), null));
    }

    private void requireReader(Jwt jwt) {
        requireUnexpiredToken(jwt, clock.instant());
        Account actor = auth.requireActiveAccount(jwt);
        if (!permissions.forUser(actor.getId()).contains(READ_ALL)) {
            throw new AccessDeniedException("Headcount plans require HEADCOUNT_PLANS_READ_ALL");
        }
    }

    // The caller of a write once lockWriter holds its account and session rows.
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

    // May the caller still write? Called once the account and session are locked (the request may have waited for
    // them) and again after every row lock the write waits for. The account and session rows stay locked, so an admin
    // lock, a logout or a role change through the account API waits for this transaction; but time passes (token or
    // session expiry) and role_permissions can still change, so all three are checked each time.
    private void requireStillWriter(Jwt jwt, Writer writer) {
        var now = clock.instant();
        requireUnexpiredToken(jwt, now);
        if (!writer.session().getUserId().equals(writer.actorId()) || !writer.session().isActive(now)) {
            throw AuthenticationFailureException.sessionInvalid();
        }
        if (!permissions.forUser(writer.actorId()).contains(WRITE_ALL)) {
            throw new AccessDeniedException("Headcount plan writes require HEADCOUNT_PLANS_WRITE_ALL");
        }
    }

    private void requireUnexpiredToken(Jwt jwt, Instant now) {
        if (jwt == null || jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(now)) {
            throw AuthenticationFailureException.sessionInvalid();
        }
    }

    private static boolean validYear(int year) {
        return year >= HeadcountPlanRequest.MIN_YEAR && year <= HeadcountPlanRequest.MAX_YEAR;
    }

    // PostgreSQL TIMESTAMPTZ keeps microseconds, so the response shows the same time a later read returns.
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static ApiException invalidField(String code, String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message, Map.of(field, message));
    }

    private static ApiException alreadyExists() {
        return new ApiException(HttpStatus.CONFLICT, "HEADCOUNT_PLAN_EXISTS",
                "Phòng ban đã có định biên cho năm này. Hãy sửa định biên đang có.");
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "HEADCOUNT_PLAN_NOT_FOUND", "Không tìm thấy định biên.");
    }
}
