package vn.ttcs.recruitment.candidateduplicate;

import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.auth.AuthService;
import vn.ttcs.recruitment.auth.AuthenticationFailureException;
import vn.ttcs.recruitment.department.DepartmentRepository;
import vn.ttcs.recruitment.requisition.RecruiterAssignmentAccess;
import vn.ttcs.recruitment.security.*;

@Component
class CandidateDuplicateAccess {
    private final AuthService auth;
    private final PermissionService permissions;
    private final DepartmentRepository departments;
    private final RecruiterAssignmentAccess assignments;
    private final Clock clock;

    CandidateDuplicateAccess(AuthService auth, PermissionService permissions, DepartmentRepository departments,
            RecruiterAssignmentAccess assignments, Clock clock) {
        this.auth = auth; this.permissions = permissions; this.departments = departments;
        this.assignments = assignments; this.clock = clock;
    }

    record Caller(UUID id, AccessScope scope, Set<UUID> managedDepartments) {
        // Only constant aliases/fragments are assembled; caller values are bound parameters.
        String visibleSql() {
            if (scope == AccessScope.ALL) return "TRUE";
            return "(EXISTS (SELECT 1 FROM requisition_recruiters team WHERE team.requisition_id=v.requisition_id"
                    + " AND team.recruiter_id=:actor)"
                    + (managedDepartments.isEmpty() ? "" : " OR r.department_id IN (:departments)") + ")";
        }
        MapSqlParameterSource parameters() {
            return new MapSqlParameterSource("actor", id).addValue("departments", managedDepartments);
        }
    }

    Caller reader(Jwt jwt) {
        if (jwt == null || jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(clock.instant()))
            throw AuthenticationFailureException.sessionInvalid();
        var account = auth.requireActiveAccount(jwt);
        var scope = AccessScope.read(permissions.forUser(account.getId()), PermissionModule.CANDIDATES).orDeny();
        var managed = scope == AccessScope.SCOPED && account.getRoles().contains(Role.HIRING_MANAGER)
                ? departments.findManagedDepartmentIds(account.getId()) : Set.<UUID>of();
        return new Caller(account.getId(), scope, managed);
    }

    void requireScope(Caller caller, UUID requisition, UUID department) {
        if (caller.scope() != AccessScope.ALL && !caller.managedDepartments().contains(department)
                && !assignments.isAssigned(requisition, caller.id()))
            throw new AccessDeniedException("Application is outside candidate scope");
    }
}
