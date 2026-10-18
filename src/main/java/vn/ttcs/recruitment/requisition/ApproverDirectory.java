package vn.ttcs.recruitment.requisition;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.department.DepartmentRepository;
import vn.ttcs.recruitment.security.PermissionService;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Task 256: reads the accounts ApproverRule is applied to. Nothing here locks: at submit the result only tells whether
 * every level can be decided; the decision itself checks the decider again on the account it has locked.
 */
@Component
public class ApproverDirectory {
    // APPROVER is company-wide by definition (role-permission matrix: "yêu cầu được chuyển cho mình duyệt"); a role
    // that may write every requisition is company-wide too. Read from role_permissions, so a grant change applies at
    // once, like every permission check.
    private static final String COMPANY_WIDE_ROLES = """
            SELECT 'APPROVER'
            UNION
            SELECT role_code FROM role_permissions WHERE permission_code = 'REQUISITIONS_WRITE_ALL'
            """;

    private final AccountRepository accounts;
    private final PermissionService permissions;
    private final DepartmentRepository departments;
    private final NamedParameterJdbcTemplate jdbc;

    public ApproverDirectory(AccountRepository accounts, PermissionService permissions, DepartmentRepository departments,
                             NamedParameterJdbcTemplate jdbc) {
        this.accounts = accounts;
        this.permissions = permissions;
        this.departments = departments;
        this.jdbc = jdbc;
    }

    public Set<String> companyWideRoles() {
        return Set.copyOf(jdbc.queryForList(COMPANY_WIDE_ROLES, Map.of(), String.class));
    }

    public ApproverCandidate candidate(Account account) {
        return candidate(account, permissions.forUser(account.getId()));
    }

    // granted: the permissions just read for this account (a decision passes the ones it re-read after its locks).
    public ApproverCandidate candidate(Account account, Set<String> granted) {
        Set<String> roles = account.getRoles().stream().map(Role::name).collect(Collectors.toUnmodifiableSet());
        return new ApproverCandidate(account.getId(), account.getFullName(), account.isAccessAllowed(), roles, granted,
                () -> departments.findManagedDepartmentIds(account.getId()));
    }

    // Everyone who may decide the level now, by name.
    public List<ApproverCandidate> admitted(ApprovalLevel level, ApprovalContext context) {
        List<Account> pool = level.approverUserId() != null
                ? accounts.findById(level.approverUserId()).stream().toList()
                : accounts.findAllById(jdbc.queryForList("SELECT user_id FROM user_roles WHERE role = :role",
                        Map.of("role", level.approverRole()), UUID.class));
        return pool.stream().map(this::candidate).filter(candidate -> ApproverRule.admits(level, context, candidate))
                .sorted(Comparator.comparing(ApproverCandidate::fullName).thenComparing(ApproverCandidate::id))
                .toList();
    }

    // Who the level is routed to, for messages: the named account's full name, or the role's display name.
    public String approverLabel(ApprovalLevel level) {
        String sql = level.approverUserId() != null
                ? "SELECT full_name FROM user_accounts WHERE id = :key"
                : "SELECT display_name FROM roles WHERE code = :key";
        Object key = level.approverUserId() != null ? level.approverUserId() : level.approverRole();
        return jdbc.queryForList(sql, Map.of("key", key), String.class).stream().findFirst()
                .orElse(String.valueOf(key));
    }
}
