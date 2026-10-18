package vn.ttcs.recruitment.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.ttcs.recruitment.security.AccessScope;
import vn.ttcs.recruitment.security.PermissionModule;
import vn.ttcs.recruitment.security.PermissionService;

import java.util.UUID;

/**
 * Task 346 (story S4-06, "Recruiter phụ trách nhận thông báo"): who is told about a withdrawal. One
 * RECRUITER_WITHDRAWAL notice to the requisition's PRIMARY recruiter, only when that account may sign in (enabled, not
 * locked) and holds CANDIDATES_READ_ALL or CANDIDATES_READ_SCOPED: an assigned PRIMARY with either code may read this
 * application (task 363), and one without (V16 keeps a recruiter assigned after losing the RECRUITER role) learns
 * nothing. One CANDIDATE_WITHDRAWAL notice to the application's own address, so a candidate notices a withdrawal made
 * with a forwarded or shared confirmation. No SUPPORTING recruiter is told. A requisition without an eligible PRIMARY
 * gets no recruiter notice, and the withdrawal still succeeds (a requisition may have no team yet, V16).
 * <p>
 * The worker applies mayReadCandidates again at send time, with the account and the team as they are then.
 */
@Component
class ApplicationWithdrawalNotices {
    private final ApplicationNoticeRepository outbox;
    private final ApplicationNoticeSettings settings;
    private final PermissionService permissions;

    ApplicationWithdrawalNotices(ApplicationNoticeRepository outbox, ApplicationNoticeSettings settings,
            PermissionService permissions) {
        this.outbox = outbox; this.settings = settings; this.permissions = permissions;
    }

    // Call in the withdrawal's own transaction, after its stage event and only when the stage changed: the withdrawal
    // commits if and only if its notices are queued. The requisition is locked by the caller (task 344), and every team
    // change locks it first, so the PRIMARY read here is the one at commit time. The permission read is plain; a role,
    // team or account change after commit is caught at send time.
    @Transactional(propagation = Propagation.MANDATORY)
    void queue(UUID applicationId, UUID requisitionId) {
        outbox.activePrimary(requisitionId).filter(this::mayReadCandidates).ifPresent(primary -> {
            UUID id = UUID.randomUUID();
            outbox.enqueue(id, ApplicationNoticeKind.RECRUITER_WITHDRAWAL, applicationId, primary,
                    settings.messageId(id), settings.recruiterDelaySeconds());
        });
        UUID id = UUID.randomUUID();
        outbox.enqueue(id, ApplicationNoticeKind.CANDIDATE_WITHDRAWAL, applicationId, null, settings.messageId(id), 0);
    }

    // Read from the database on every call, so a grant removed from a role counts at once. By permission code, never
    // by role name.
    boolean mayReadCandidates(UUID accountId) {
        return AccessScope.read(permissions.forUser(accountId), PermissionModule.CANDIDATES) != AccessScope.NONE;
    }
}
