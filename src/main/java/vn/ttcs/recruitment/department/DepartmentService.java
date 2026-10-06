package vn.ttcs.recruitment.department;

import org.springframework.dao.DataIntegrityViolationException;
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
import vn.ttcs.recruitment.security.PermissionService;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class DepartmentService {
    private final DepartmentRepository departments;
    private final AccountRepository accounts;
    private final AuthSessionRepository sessions;
    private final AuthService auth;
    private final PermissionService permissions;
    private final Clock clock;

    public DepartmentService(DepartmentRepository departments, AccountRepository accounts,
                             AuthSessionRepository sessions, AuthService auth, PermissionService permissions,
                             Clock clock) {
        this.departments = departments;
        this.accounts = accounts;
        this.sessions = sessions;
        this.auth = auth;
        this.permissions = permissions;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DepartmentPage list(Jwt jwt, String query, Boolean active, int page, int size) {
        requireReadAccess(jwt);
        if (page < 0 || size < 1 || size > 100 || (query != null && query.length() > 255)) {
            throw new DepartmentException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "Trang, số lượng hoặc từ khóa tìm kiếm phòng ban không hợp lệ.");
        }
        return departments.search(query, active, page, size);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DepartmentView get(Jwt jwt, UUID id) {
        requireReadAccess(jwt);
        return departments.findById(id).orElseThrow(DepartmentService::notFound);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<DepartmentTreeNode> tree(Jwt jwt) {
        requireReadAccess(jwt);
        var nodes = new LinkedHashMap<UUID, DepartmentTreeNode>();
        for (var department : departments.findAll()) {
            nodes.put(department.id(), DepartmentTreeNode.from(department));
        }
        var roots = new ArrayList<DepartmentTreeNode>();
        for (var node : nodes.values()) {
            if (node.parentId() == null) {
                roots.add(node);
            } else {
                var parent = nodes.get(node.parentId());
                if (parent == null) {
                    throw invalidTree();
                }
                parent.children().add(node);
            }
        }
        // Iterative traversal detects disconnected cycles in legacy/manual data before JSON serialization.
        var pending = new ArrayDeque<>(roots);
        var visited = new HashSet<UUID>();
        while (!pending.isEmpty()) {
            var node = pending.removeFirst();
            if (!visited.add(node.id())) {
                throw invalidTree();
            }
            pending.addAll(node.children());
        }
        if (visited.size() != nodes.size()) {
            throw invalidTree();
        }
        return List.copyOf(roots);
    }

    @Transactional
    public DepartmentView create(Jwt jwt, DepartmentRequest request) {
        Account manager = requireWriteAccess(jwt, request.managerUserId());
        requireAccessibleManager(manager);
        UUID id = UUID.randomUUID();
        validateParent(id, request.parentId());
        if (departments.codeExists(request.code(), null)) {
            throw duplicateCode();
        }
        try {
            departments.insert(id, request, clock.instant());
        } catch (DataIntegrityViolationException exception) {
            throw translateDuplicateCode(exception);
        }
        return departments.findById(id).orElseThrow(DepartmentService::notFound);
    }

    @Transactional
    public DepartmentView update(Jwt jwt, UUID id, DepartmentRequest request) {
        Account manager = requireWriteAccess(jwt, request.managerUserId());
        DepartmentView previous = departments.findById(id).orElseThrow(DepartmentService::notFound);
        boolean changingManager = !Objects.equals(previous.managerUserId(), request.managerUserId());
        boolean reactivating = !previous.active() && request.active();
        if (changingManager || reactivating) {
            requireAccessibleManager(manager);
        }
        validateParent(id, request.parentId());
        if (departments.codeExists(request.code(), id)) {
            throw duplicateCode();
        }
        try {
            departments.update(id, request);
        } catch (DataIntegrityViolationException exception) {
            throw translateDuplicateCode(exception);
        }
        return departments.findById(id).orElseThrow(DepartmentService::notFound);
    }

    private void requireReadAccess(Jwt jwt) {
        requireUnexpiredToken(jwt, clock.instant());
        Account actor = auth.requireActiveAccount(jwt);
        if (!permissions.forUser(actor.getId()).contains("ORGANIZATION_READ_ALL")) {
            throw new AccessDeniedException("Department access requires ORGANIZATION_READ_ALL");
        }
    }

    private Account requireWriteAccess(Jwt jwt, UUID managerId) {
        UUID actorId;
        UUID sessionId;
        try {
            actorId = UUID.fromString(jwt.getSubject());
            sessionId = UUID.fromString(jwt.getId());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw AuthenticationFailureException.sessionInvalid();
        }
        var accountIds = new HashSet<UUID>();
        accountIds.add(actorId);
        accountIds.add(managerId);
        // Match account administration: all required accounts in UUID order, then the actor's session.
        var lockedAccounts = accounts.findAllByIdForUpdate(accountIds);
        var session = sessions.findByIdForUpdate(sessionId)
                .orElseThrow(AuthenticationFailureException::sessionInvalid);
        departments.acquireTreeWriteLock();

        // A request may have waited for account, session or tree locks. Recheck authorization now.
        var now = clock.instant();
        Account actor = lockedAccounts.stream()
                .filter(account -> account.getId().equals(actorId) && account.isAccessAllowed())
                .findFirst().orElseThrow(AuthenticationFailureException::sessionInvalid);
        requireUnexpiredToken(jwt, now);
        if (!session.getUserId().equals(actorId) || !session.isActive(now)) {
            throw AuthenticationFailureException.sessionInvalid();
        }
        if (!permissions.forUser(actorId).contains("ORGANIZATION_WRITE_ALL")) {
            throw new AccessDeniedException("Department management requires ORGANIZATION_WRITE_ALL");
        }
        return lockedAccounts.stream().filter(account -> account.getId().equals(managerId))
                .findFirst().orElseThrow(DepartmentService::invalidManager);
    }

    private void requireUnexpiredToken(Jwt jwt, Instant now) {
        if (jwt == null || jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(now)) {
            throw AuthenticationFailureException.sessionInvalid();
        }
    }

    private void requireAccessibleManager(Account manager) {
        if (!manager.isAccessAllowed()) {
            throw invalidManager();
        }
    }

    private void validateParent(UUID id, UUID parentId) {
        if (parentId == null) {
            return;
        }
        var parents = departments.findParents();
        if (!parents.containsKey(parentId)) {
            throw new DepartmentException(HttpStatus.BAD_REQUEST, "INVALID_DEPARTMENT_PARENT",
                    "Phòng ban cha không tồn tại.");
        }
        var visited = new HashSet<UUID>();
        UUID ancestor = parentId;
        while (ancestor != null) {
            if (ancestor.equals(id)) {
                throw new DepartmentException(HttpStatus.CONFLICT, "DEPARTMENT_CYCLE",
                        "Phòng ban không thể trực thuộc chính mình hoặc một phòng ban con của mình.");
            }
            if (!visited.add(ancestor) || !parents.containsKey(ancestor)) {
                throw invalidTree();
            }
            ancestor = parents.get(ancestor);
        }
    }

    private RuntimeException translateDuplicateCode(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && "23505".equals(sql.getSQLState())
                    && sql.getMessage() != null && sql.getMessage().contains("departments_code_key")) {
                return duplicateCode();
            }
        }
        return exception;
    }

    private static DepartmentException notFound() {
        return new DepartmentException(HttpStatus.NOT_FOUND, "DEPARTMENT_NOT_FOUND", "Không tìm thấy phòng ban.");
    }

    private static DepartmentException duplicateCode() {
        return new DepartmentException(HttpStatus.CONFLICT, "DEPARTMENT_CODE_EXISTS", "Mã phòng ban đã được sử dụng.");
    }

    private static DepartmentException invalidManager() {
        return new DepartmentException(HttpStatus.BAD_REQUEST, "INVALID_DEPARTMENT_MANAGER",
                "Người quản lý phải là tài khoản tồn tại và được phép truy cập hệ thống khi được bổ nhiệm.");
    }

    private static DepartmentException invalidTree() {
        return new DepartmentException(HttpStatus.CONFLICT, "DEPARTMENT_TREE_INVALID",
                "Dữ liệu cây phòng ban hiện có không hợp lệ. Vui lòng kiểm tra quan hệ phòng ban cha.");
    }
}
