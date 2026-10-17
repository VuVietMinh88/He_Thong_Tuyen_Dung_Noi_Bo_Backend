package vn.ttcs.recruitment.candidatemerge;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import vn.ttcs.recruitment.auth.AuthenticationFailureException;
import vn.ttcs.recruitment.department.DepartmentRepository;
import vn.ttcs.recruitment.requisition.RecruiterAssignmentAccess;
import vn.ttcs.recruitment.security.*;

/** Fresh JDBC checks avoid persistence-context account/role snapshots after waiting for locks. */
@Component
class CandidateMergeAccess {
    private final JdbcTemplate jdbc;
    private final PermissionService permissions;
    private final DepartmentRepository departments;
    private final Clock clock;

    CandidateMergeAccess(JdbcTemplate jdbc, PermissionService permissions, DepartmentRepository departments, Clock clock) {
        this.jdbc=jdbc; this.permissions=permissions; this.departments=departments; this.clock=clock;
    }

    UUID actor(Jwt jwt, boolean lock) {
        try {
            if (jwt==null || jwt.getExpiresAt()==null || !jwt.getExpiresAt().isAfter(clock.instant())) throw invalid();
            UUID id=UUID.fromString(jwt.getSubject()), session=UUID.fromString(jwt.getId());
            var accounts=jdbc.query("SELECT enabled,admin_locked_at FROM user_accounts WHERE id=?"+(lock?" FOR UPDATE":""),
                    (rs,n)->rs.getBoolean(1)&&rs.getTimestamp(2)==null,id);
            if (accounts.isEmpty() || !accounts.getFirst()) throw invalid();
            var sessions=jdbc.query("SELECT user_id,revoked_at,expires_at FROM auth_sessions WHERE id=?"+(lock?" FOR UPDATE":""),
                    (rs,n)->id.equals(rs.getObject(1,UUID.class)) && rs.getTimestamp(2)==null
                            && rs.getTimestamp(3).toInstant().isAfter(clock.instant()),session);
            if (sessions.isEmpty() || !sessions.getFirst() || !jwt.getExpiresAt().isAfter(clock.instant())) throw invalid();
            return id;
        } catch (IllegalArgumentException | NullPointerException malformed) { throw invalid(); }
    }

    void require(Jwt jwt, List<CandidateMergeSnapshot.Member> members, boolean write) {
        UUID actor=actor(jwt,false);
        var codes=permissions.forUser(actor);
        var read=AccessScope.read(codes,PermissionModule.CANDIDATES).orDeny();
        var edit=write?AccessScope.write(codes,PermissionModule.CANDIDATES).orDeny():AccessScope.ALL;
        boolean manager=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM user_roles WHERE user_id=? AND role='HIRING_MANAGER')",Boolean.class,actor);
        Set<UUID> managed=manager?departments.findManagedDepartmentIds(actor):Set.of();
        if (members.isEmpty()) return;
        var parameters=new org.springframework.jdbc.core.namedparam.MapSqlParameterSource(RecruiterAssignmentAccess.ASSIGNEE,actor)
                .addValue("ids",members.stream().map(CandidateMergeSnapshot.Member::applicationId).toList())
                .addValue("departments",managed);
        boolean needsScope=read!=AccessScope.ALL || edit!=AccessScope.ALL;
        String scope=needsScope?"(r.id IN ("+RecruiterAssignmentAccess.ASSIGNED_REQUISITION_IDS+")"
                +(managed.isEmpty()?"":" OR r.department_id IN (:departments)")+")":"TRUE";
        var allowed=new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc).query("""
                SELECT a.id FROM job_applications a JOIN recruitment_requisitions r ON r.id=a.requisition_id
                WHERE a.id IN (:ids) AND
                """+scope,parameters,(rs,n)->rs.getObject(1,UUID.class));
        if (allowed.size()!=members.size()) throw new AccessDeniedException("Candidate operation is outside current scope");
    }

    private static AuthenticationFailureException invalid() { return AuthenticationFailureException.sessionInvalid(); }
}
