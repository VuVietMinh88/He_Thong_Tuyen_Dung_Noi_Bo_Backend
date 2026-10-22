package vn.ttcs.recruitment.cvsuggestion;

import java.time.Clock;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.auth.*;
import vn.ttcs.recruitment.requisition.RecruiterAssignmentAccess;
import vn.ttcs.recruitment.security.*;

/** Candidate permissions are independent of requisition and posting permissions. */
@Component
class CvSuggestionAccess {
    private final AccountRepository accounts;
    private final AuthSessionRepository sessions;
    private final AuthService auth;
    private final PermissionService permissions;
    private final RecruiterAssignmentAccess assignments;
    private final Clock clock;
    CvSuggestionAccess(AccountRepository accounts, AuthSessionRepository sessions, AuthService auth,
            PermissionService permissions, RecruiterAssignmentAccess assignments, Clock clock) {
        this.accounts=accounts; this.sessions=sessions; this.auth=auth; this.permissions=permissions;
        this.assignments=assignments; this.clock=clock;
    }
    record Writer(UUID id, AuthSession session) { }
    record Caller(UUID id, AccessScope scope) { }
    Writer lock(Jwt jwt) {
        try {
            UUID id=UUID.fromString(jwt.getSubject()), session=UUID.fromString(jwt.getId());
            accounts.findByIdForUpdate(id).filter(Account::isAccessAllowed)
                    .orElseThrow(AuthenticationFailureException::sessionInvalid);
            return new Writer(id, sessions.findByIdForUpdate(session)
                    .orElseThrow(AuthenticationFailureException::sessionInvalid));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw AuthenticationFailureException.sessionInvalid();
        }
    }
    Caller writer(Jwt jwt, Writer writer) {
        expiry(jwt);
        if (!writer.session().getUserId().equals(writer.id()) || !writer.session().isActive(clock.instant()))
            throw AuthenticationFailureException.sessionInvalid();
        var codes=permissions.forUser(writer.id());
        var read=AccessScope.read(codes, PermissionModule.CANDIDATES).orDeny();
        var write=AccessScope.write(codes, PermissionModule.CANDIDATES).orDeny();
        return new Caller(writer.id(), read == AccessScope.ALL && write == AccessScope.ALL ? AccessScope.ALL : AccessScope.SCOPED);
    }
    Caller reader(Jwt jwt) {
        expiry(jwt);
        var account=auth.requireActiveAccount(jwt);
        return new Caller(account.getId(), AccessScope.read(permissions.forUser(account.getId()), PermissionModule.CANDIDATES).orDeny());
    }
    void scope(Caller caller, UUID requisition) {
        if (caller.scope()!=AccessScope.ALL && !assignments.isAssigned(requisition, caller.id()))
            throw new AccessDeniedException("Application is outside assigned candidate scope");
    }
    private void expiry(Jwt jwt) {
        if (jwt==null || jwt.getExpiresAt()==null || !jwt.getExpiresAt().isAfter(clock.instant()))
            throw AuthenticationFailureException.sessionInvalid();
    }
}
