package vn.ttcs.recruitment.approval;

import jakarta.validation.Validator;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.auth.AuthService;
import vn.ttcs.recruitment.auth.AuthSessionRepository;
import vn.ttcs.recruitment.auth.AuthenticationFailureException;
import vn.ttcs.recruitment.security.PermissionService;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.*;

@Service
public class ApprovalFlowService {
    private final ApprovalFlowRepository flows;
    private final AccountRepository accounts;
    private final AuthSessionRepository sessions;
    private final AuthService auth;
    private final PermissionService permissions;
    private final Validator validator;
    private final Clock clock;
    public ApprovalFlowService(ApprovalFlowRepository flows, AccountRepository accounts, AuthSessionRepository sessions,
                               AuthService auth, PermissionService permissions, Validator validator, Clock clock) {
        this.flows=flows; this.accounts=accounts; this.sessions=sessions; this.auth=auth;
        this.permissions=permissions; this.validator=validator; this.clock=clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ApprovalFlowView.Page list(Jwt jwt, UUID departmentId, int page, int size) {
        readAccess(jwt);
        if (page < 0 || size < 1 || size > 100) throw invalid(Map.of("page", "Trang từ 0; kích thước trang từ 1 đến 100."));
        return flows.list(departmentId, page, size);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ApprovalFlowView get(Jwt jwt, UUID id, Integer version) {
        readAccess(jwt);
        if (version != null && version < 1) throw invalid(Map.of("version", "Phiên bản phải lớn hơn 0."));
        return flows.find(id, version).orElseThrow(ApprovalFlowService::notFound);
    }

    @Transactional
    public ApprovalFlowView create(Jwt jwt, ApprovalFlowRequest request) {
        validate(request);
        if (request.expectedVersion() != null) throw invalid(Map.of("expectedVersion", "Không gửi phiên bản khi tạo cấu hình."));
        UUID actor = writeAccess(jwt, request);
        if (flows.forDepartment(request.departmentId()).isPresent())
            throw failure(HttpStatus.CONFLICT, "APPROVAL_FLOW_EXISTS", "Phòng ban đã có cấu hình phê duyệt.");
        UUID id = UUID.randomUUID();
        flows.createFlow(id, request.departmentId());
        flows.publish(id, 1, request, actor, clock.instant());
        return flows.find(id, null).orElseThrow(ApprovalFlowService::notFound);
    }

    @Transactional
    public ApprovalFlowView update(Jwt jwt, UUID id, ApprovalFlowRequest request) {
        validate(request);
        if (request.expectedVersion() == null) throw invalid(Map.of("expectedVersion", "Phải gửi phiên bản đang sửa."));
        UUID actor = writeAccess(jwt, request);
        var previous = flows.find(id, null).orElseThrow(ApprovalFlowService::notFound);
        if (!previous.departmentId().equals(request.departmentId()))
            throw invalid(Map.of("departmentId", "Không đổi phòng ban của cấu hình đã tạo."));
        if (previous.version() != request.expectedVersion())
            throw failure(HttpStatus.CONFLICT, "APPROVAL_VERSION_CONFLICT", "Cấu hình đã thay đổi. Vui lòng tải phiên bản mới nhất.");
        flows.publish(id, Math.addExact(previous.version(), 1), request, actor, clock.instant());
        return flows.find(id, null).orElseThrow(ApprovalFlowService::notFound);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ApprovalFlowView preview(Jwt jwt, ApprovalFlowPreviewRequest request) {
        readAccess(jwt);
        validateBean(request);
        if (!flows.activeDepartment(request.departmentId()))
            throw invalid(Map.of("departmentId", "Phòng ban không tồn tại hoặc đã ngừng áp dụng."));
        var id = flows.forDepartment(request.departmentId()).orElseThrow(ApprovalFlowService::notFound);
        var flow = flows.find(id, null).orElseThrow(ApprovalFlowService::notFound);
        var selected = flow.steps().stream().filter(s -> s.salaryThreshold() == null
                || request.proposedSalary().compareTo(s.salaryThreshold()) > 0).toList();
        return new ApprovalFlowView(flow.id(), flow.departmentId(), flow.version(), flow.name(), flow.createdBy(), flow.createdAt(), selected);
    }

    private void validate(ApprovalFlowRequest request) {
        validateBean(request);
        var errors = new LinkedHashMap<String,String>();
        var targets = new HashSet<String>();
        BigDecimal previousThreshold = null;
        for (int i = 0; i < request.steps().size(); i++) {
            var step = request.steps().get(i);
            String path = "steps[" + i + "]";
            if (step.position() != i + 1) errors.put(path + ".position", "Cấp duyệt phải liên tiếp từ 1, đúng thứ tự trong danh sách.");
            if ((step.approverUserId() == null) == (step.approverRole() == null))
                errors.put(path + ".approverRole", "Chọn đúng một người hoặc một vai trò duyệt.");
            if (i == 0 && step.salaryThreshold() != null)
                errors.put(path + ".salaryThreshold", "Cấp đầu tiên phải áp dụng cho mọi mức lương (null).");
            if (previousThreshold != null && (step.salaryThreshold() == null || step.salaryThreshold().compareTo(previousThreshold) < 0))
                errors.put(path + ".salaryThreshold", "Các cấp cơ bản phải đứng trước, ngưỡng tăng thêm không được giảm.");
            previousThreshold = step.salaryThreshold();
            String target = step.approverUserId() == null ? "role:" + step.approverRole() : "user:" + step.approverUserId();
            if (!targets.add(target)) errors.put(path + ".approverRole", "Không lặp người hoặc vai trò trong chuỗi duyệt.");
            if (step.approverRole() != null && !flows.eligibleRole(step.approverRole()))
                errors.put(path + ".approverRole", "Vai trò phải là vai trò nội bộ có quyền ghi yêu cầu tuyển dụng.");
        }
        if (!errors.isEmpty()) throw invalid(errors);
    }

    private void validateBean(Object request) {
        if (request == null) throw invalid(Map.of("body", "Phải gửi cấu hình."));
        var errors = new LinkedHashMap<String,String>();
        validator.validate(request).forEach(v -> errors.putIfAbsent(v.getPropertyPath().toString(), v.getMessage()));
        if (!errors.isEmpty()) throw invalid(errors);
    }

    private void readAccess(Jwt jwt) {
        requireUnexpired(jwt);
        requirePermission(auth.requireActiveAccount(jwt), "REQUISITIONS_READ_ALL");
    }

    private UUID writeAccess(Jwt jwt, ApprovalFlowRequest request) {
        requireUnexpired(jwt);
        UUID actorId, sessionId;
        try { actorId=UUID.fromString(jwt.getSubject()); sessionId=UUID.fromString(jwt.getId()); }
        catch (IllegalArgumentException | NullPointerException ex) { throw AuthenticationFailureException.sessionInvalid(); }
        var ids = new HashSet<UUID>(); ids.add(actorId);
        request.steps().stream().map(ApprovalFlowRequest.Step::approverUserId).filter(Objects::nonNull).forEach(ids::add);
        var locked = accounts.findAllByIdForUpdate(ids);
        var session = sessions.findByIdForUpdate(sessionId).orElseThrow(AuthenticationFailureException::sessionInvalid);
        // Same account -> session -> department order as existing administrative writers.
        var department = flows.lockDepartment(request.departmentId());
        requireUnexpired(jwt);
        var actor = locked.stream().filter(a -> a.getId().equals(actorId) && a.isAccessAllowed()).findFirst()
                .orElseThrow(AuthenticationFailureException::sessionInvalid);
        if (!session.getUserId().equals(actorId) || !session.isActive(clock.instant()))
            throw AuthenticationFailureException.sessionInvalid();
        requirePermission(actor, "REQUISITIONS_WRITE_ALL");
        if (department.isEmpty() || !department.get())
            throw invalid(Map.of("departmentId", "Phòng ban không tồn tại hoặc đã ngừng áp dụng."));
        var errors = new LinkedHashMap<String,String>();
        for (int i = 0; i < request.steps().size(); i++) {
            var step = request.steps().get(i);
            if (step.approverUserId() != null) {
                var user = locked.stream().filter(a -> a.getId().equals(step.approverUserId()) && a.isAccessAllowed()).findFirst();
                var grants = user.isEmpty() ? Set.<String>of() : permissions.forUser(step.approverUserId());
                if (user.isEmpty() || (!grants.contains("REQUISITIONS_WRITE_ALL") && !grants.contains("REQUISITIONS_WRITE_SCOPED")))
                    errors.put("steps[" + i + "].approverUserId", "Người duyệt phải được phép truy cập và có quyền ghi yêu cầu tuyển dụng.");
            } else if (!flows.eligibleRole(step.approverRole())) {
                errors.put("steps[" + i + "].approverRole", "Vai trò duyệt không còn hợp lệ.");
            }
        }
        if (!errors.isEmpty()) throw invalid(errors);
        return actorId;
    }

    private void requirePermission(Account actor, String permission) {
        if ((!actor.getRoles().contains(Role.ADMIN) && !actor.getRoles().contains(Role.HR_MANAGER))
                || !permissions.forUser(actor.getId()).contains(permission))
            throw new AccessDeniedException("Approval configuration requires HR_MANAGER/ADMIN and " + permission);
    }
    private void requireUnexpired(Jwt jwt) {
        if (jwt == null || jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(clock.instant()))
            throw AuthenticationFailureException.sessionInvalid();
    }
    private static ApprovalFlowException invalid(Map<String,String> fields) {
        return new ApprovalFlowException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Vui lòng kiểm tra cấu hình phê duyệt.", fields);
    }
    private static ApprovalFlowException notFound() {
        return failure(HttpStatus.NOT_FOUND, "APPROVAL_FLOW_NOT_FOUND", "Không tìm thấy cấu hình hoặc phiên bản phê duyệt.");
    }
    private static ApprovalFlowException failure(HttpStatus status, String code, String message) {
        return new ApprovalFlowException(status, code, message, Map.of());
    }
}
