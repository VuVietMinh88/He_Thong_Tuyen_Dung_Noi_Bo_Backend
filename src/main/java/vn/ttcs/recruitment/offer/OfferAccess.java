package vn.ttcs.recruitment.offer;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.auth.AuthService;
import vn.ttcs.recruitment.auth.AuthSession;
import vn.ttcs.recruitment.auth.AuthSessionRepository;
import vn.ttcs.recruitment.auth.AuthenticationFailureException;
import vn.ttcs.recruitment.candidate.ApplicationAccess;
import vn.ttcs.recruitment.candidate.CandidateAccess;
import vn.ttcs.recruitment.candidate.CandidateAccessKind;
import vn.ttcs.recruitment.candidate.CandidateReader;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.requisition.RecruiterAssignmentAccess;
import vn.ttcs.recruitment.security.AccessScope;
import vn.ttcs.recruitment.security.PermissionModule;
import vn.ttcs.recruitment.security.PermissionService;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Shared authorization/parent lock order for drafting, approval and later delivery. Caller owns transaction. */
@Component
public class OfferAccess {
    private final JdbcTemplate jdbc;
    private final AccountRepository accounts;
    private final AuthSessionRepository sessions;
    private final AuthService auth;
    private final CandidateAccess candidates;
    private final RecruiterAssignmentAccess assignments;
    private final PermissionService permissions;
    private final Clock clock;

    public OfferAccess(JdbcTemplate jdbc, AccountRepository accounts, AuthSessionRepository sessions, AuthService auth,
        CandidateAccess candidates, RecruiterAssignmentAccess assignments, PermissionService permissions, Clock clock) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.sessions = sessions;
        this.auth = auth;
        this.candidates = candidates;
        this.assignments = assignments;
        this.permissions = permissions;
        this.clock = clock;
    }

    public record Writer(UUID id, String name, Set<Role> roles, AuthSession session) {
    }

    public record Context(UUID applicationId, UUID requisitionId, UUID candidateId, UUID positionId,
        String candidateName, String email, String positionTitle, String stage, String requisitionStatus) {
    }

    public Writer lockWriter(Jwt jwt) {
        jdbc.execute("SET LOCAL lock_timeout='2s'");
        jdbc.execute("SET LOCAL statement_timeout='3s'");
        valid(jwt);
        UUID id;
        UUID session;
        try {
            id = UUID.fromString(jwt.getSubject());
            session = UUID.fromString(jwt.getId());
        }
        catch (IllegalArgumentException | NullPointerException e) {
            throw AuthenticationFailureException.sessionInvalid();
        }
        Account actor = accounts.findByIdForUpdate(id).filter(Account::isAccessAllowed).orElseThrow(AuthenticationFailureException::sessionInvalid);
        Writer writer = new Writer(id, actor.getFullName(), Set.copyOf(actor.getRoles()), sessions.findByIdForUpdate(session).orElseThrow(AuthenticationFailureException::sessionInvalid));
        fresh(jwt, writer);
        return writer;
    }

    public void fresh(Jwt jwt, Writer writer) {
        valid(jwt);
        if (!writer.session().getUserId().equals(writer.id()) || !writer.session().isActive(clock.instant())) throw AuthenticationFailureException.sessionInvalid();
    }

    public Account reader(Jwt jwt) {
        valid(jwt);
        return auth.requireActiveAccount(jwt);
    }

    public void requireWriterRole(UUID actor, Set<Role> roles) {
        if (roles.stream().noneMatch(r -> r == Role.RECRUITER || r == Role.HR_MANAGER || r == Role.ADMIN)) throw new AccessDeniedException("Offer drafting role required");
        AccessScope.write(permissions.forUser(actor), PermissionModule.OFFERS).orDeny();
    }

    public ApplicationAccess requireWriteScope(Jwt jwt, Writer writer, UUID applicationId) {
        fresh(jwt, writer);
        requireWriterRole(writer.id(), writer.roles());
        return scope(writer.id(), applicationId, true);
    }

    public ApplicationAccess scope(UUID actor, UUID applicationId, boolean write) {
        var granted = permissions.forUser(actor);
        AccessScope offers = AccessScope.read(granted, PermissionModule.OFFERS).orDeny();
        AccessScope candidate = AccessScope.read(granted, PermissionModule.CANDIDATES).orDeny();
        AccessScope writing = write ? AccessScope.write(granted, PermissionModule.OFFERS).orDeny() : AccessScope.ALL;
        boolean scoped = offers == AccessScope.SCOPED || candidate == AccessScope.SCOPED || writing == AccessScope.SCOPED;
        var reader = new CandidateReader(actor, scoped ? AccessScope.SCOPED : AccessScope.ALL);
        ApplicationAccess result = candidates.findApplication(reader, applicationId).orElseThrow(OfferAccess::notFound);
        if (!result.readable()) throw new ScopeDenied(reader, result.candidateId(), applicationId);
        if (write && scoped) {
            // Department managers may read a profile; writing its offer still requires current recruiter assignment.
            UUID requisition = jdbc.queryForObject("SELECT requisition_id FROM job_applications WHERE id=?", UUID.class, applicationId);
            if (!assignments.isAssigned(requisition, actor)) {
                throw new ScopeDenied(reader, result.candidateId(), applicationId);
            }
        }
        return result;
    }

    public Context context(UUID applicationId) {
        return jdbc.query("""
                SELECT a.id,a.requisition_id,ca.candidate_id,r.position_id,a.full_name,a.email,a.stage,
                    r.status AS requisition_status,p.name AS position_title
                FROM job_applications a JOIN candidate_applications ca ON ca.application_id=a.id
                JOIN recruitment_requisitions r ON r.id=a.requisition_id JOIN positions p ON p.id=r.position_id WHERE a.id=?
                """, (r, n) -> new Context(r.getObject("id", UUID.class), r.getObject("requisition_id", UUID.class), r.getObject("candidate_id", UUID.class),
            r.getObject("position_id", UUID.class), r.getString("full_name"), r.getString("email"), r.getString("position_title"),
            r.getString("stage"), r.getString("requisition_status")), applicationId).stream().findFirst().orElseThrow(OfferAccess::notFound);
    }

    public Context lockParents(UUID applicationId) {
        Context initial = context(applicationId);
        jdbc.queryForList("SELECT id FROM recruitment_requisitions WHERE id=? FOR NO KEY UPDATE", UUID.class, initial.requisitionId());
        jdbc.queryForList("SELECT id FROM job_applications WHERE id=? FOR NO KEY UPDATE", UUID.class, applicationId);
        return context(applicationId);
    }

    public void requireActive(Context context) {
        if (!"OFFER".equals(context.stage()) || !"APPROVED".equals(context.requisitionStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "OFFER_APPLICATION_INACTIVE", "Hồ sơ phải ở giai đoạn Offer và yêu cầu tuyển dụng đang hoạt động.");
        }
    }

    public void audit(Writer writer, Context context) {
        audit(writer.id(), context);
    }

    public void audit(UUID actor, Context context) {
        var granted = permissions.forUser(actor);
        boolean all = AccessScope.read(granted, PermissionModule.OFFERS) == AccessScope.ALL
        && AccessScope.read(granted, PermissionModule.CANDIDATES) == AccessScope.ALL;
        candidates.recordGranted(new CandidateReader(actor, all ? AccessScope.ALL : AccessScope.SCOPED), CandidateAccessKind.PROFILE, context.candidateId(), List.of(context.applicationId()));
    }

    /** Called only after the transaction that refused access has rolled back. */
    public void auditDenied(ScopeDenied denied) {
        candidates.recordDenied(denied.reader, CandidateAccessKind.PROFILE, denied.candidateId, denied.applicationId);
    }

    public static final class ScopeDenied extends AccessDeniedException {
        private final CandidateReader reader;
        private final UUID candidateId;
        private final UUID applicationId;
        private ScopeDenied(CandidateReader reader, UUID candidateId, UUID applicationId) {
            super("Application outside offer scope");
            this.reader = reader;
            this.candidateId = candidateId;
            this.applicationId = applicationId;
        }
    }

    public Set<String> grants(UUID actor) {
        return permissions.forUser(actor);
    }

    private void valid(Jwt jwt) {
        if (jwt == null || jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(clock.instant())) throw AuthenticationFailureException.sessionInvalid();
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND", "Không tìm thấy hồ sơ ứng tuyển.");
    }
}
