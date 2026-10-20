package vn.ttcs.recruitment.invitation;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.ttcs.recruitment.auth.passwordreset.ResetTokenGenerator;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.interview.InterviewNotifications;
import vn.ttcs.recruitment.interview.InterviewView;
import vn.ttcs.recruitment.messaging.EmailMessageLog;
import vn.ttcs.recruitment.messaging.EmailTemplateType;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.UUID;

/** All changes join the caller's scheduling transaction. No message is sent before that transaction commits. */
@Service
public class InterviewInvitationLifecycle implements InterviewNotifications {
    private final JdbcTemplate jdbc;
    private final InterviewInvitationSettings settings;
    private final InterviewInvitationCrypto crypto;
    private final InterviewInvitationOutboxRepository outbox;
    private final ResetTokenGenerator tokens;
    private final Clock clock;
    private final EmailMessageLog messages;

    // Task 533: every candidate email is recorded in email_messages; the first invitation honours the
    // INTERVIEW_INVITATION automatic-send flag.
    public InterviewInvitationLifecycle(JdbcTemplate jdbc, InterviewInvitationSettings settings,
            InterviewInvitationCrypto crypto, InterviewInvitationOutboxRepository outbox,
            ResetTokenGenerator tokens, Clock clock, EmailMessageLog messages) {
        this.jdbc = jdbc; this.settings = settings; this.crypto = crypto; this.outbox = outbox;
        this.tokens = tokens; this.clock = clock; this.messages = messages;
    }

    @Override public void requireReady() { settings.requireReady(); }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void scheduled(InterviewView current) {
        if (!settings.enabled()) return;
        settings.requireReady();
        revoke(current.id());
        Candidate candidate = candidate(current.applicationId());
        Invitation invitation = createInvitation(current);
        enqueue(current, candidate, null, "INVITATION", invitation, null);
        for (UUID participant : current.participantIds()) {
            enqueue(current, candidate, participant, "INVITATION", invitation, null);
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void changed(InterviewView previous, InterviewView current) {
        // Even when sending has been disabled, old links must never remain valid after a schedule change.
        revoke(current.id());
        if (!settings.enabled()) return;
        settings.requireReady();
        Candidate candidate = candidate(current.applicationId());
        Invitation invitation = createInvitation(current);
        enqueue(current, candidate, null, "UPDATED", invitation, null);
        var previousPanel = new HashSet<>(previous.participantIds());
        var currentPanel = new HashSet<>(current.participantIds());
        for (UUID participant : current.participantIds()) {
            enqueue(current, candidate, participant, previousPanel.contains(participant) ? "UPDATED" : "INVITATION",
                    invitation, null);
        }
        for (UUID participant : previous.participantIds()) {
            if (!currentPanel.contains(participant)) {
                // Removed panel members receive only the old appointment details, never its replacement meeting URL.
                enqueue(current, previous, candidate, participant, "REMOVED", null, null);
            }
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelled(InterviewView previous, InterviewView current, String reason) {
        revoke(current.id());
        if (!settings.enabled()) return;
        settings.requireReady();
        Candidate candidate = candidate(current.applicationId());
        enqueue(current, candidate, null, "CANCELLED", null, reason);
        for (UUID participant : previous.participantIds()) {
            enqueue(current, candidate, participant, "CANCELLED", null, reason);
        }
    }

    private Invitation createInvitation(InterviewView view) {
        Instant now = clock.instant();
        Instant expires = now.plus(Duration.ofDays(7));
        if (view.startsAt().isBefore(expires)) expires = view.startsAt();
        if (!expires.isAfter(now)) {
            throw new ApiException(HttpStatus.CONFLICT, "INTERVIEW_ALREADY_STARTED",
                    "Không thể gửi lời mời cho lịch phỏng vấn đã bắt đầu.");
        }
        UUID id = UUID.randomUUID();
        String token = tokens.create();
        jdbc.update("""
                INSERT INTO interview_invitations(id,interview_id,application_id,schedule_version,token_hash,
                    expires_at,created_at) VALUES (?,?,?,?,?,?,?)
                """, id, view.id(), view.applicationId(), view.version(), tokens.hash(token),
                Timestamp.from(expires), Timestamp.from(now));
        return new Invitation(id, token);
    }

    private void revoke(UUID interviewId) {
        jdbc.update("UPDATE interview_invitations SET revoked_at=? WHERE interview_id=? AND revoked_at IS NULL",
                Timestamp.from(clock.instant()), interviewId);
        outbox.cancelPending(interviewId);
    }

    private Candidate candidate(UUID applicationId) {
        return jdbc.queryForObject("SELECT email,full_name,posting_title FROM job_applications WHERE id=?",
                (row, number) -> new Candidate(row.getString(1), row.getString(2), row.getString(3)), applicationId);
    }

    private void enqueue(InterviewView current, Candidate candidate, UUID user, String kind,
                         Invitation invitation, String reason) {
        enqueue(current, current, candidate, user, kind, invitation, reason);
    }

    private void enqueue(InterviewView current, InterviewView details, Candidate candidate, UUID user, String kind,
                         Invitation invitation, String reason) {
        Recipient recipient;
        if (user == null) recipient = new Recipient(candidate.email(), candidate.name());
        else {
            var recipients = jdbc.query("""
                    SELECT email,full_name FROM user_accounts WHERE id=? AND enabled AND admin_locked_at IS NULL
                    """, (row, number) -> new Recipient(row.getString(1), row.getString(2)), user);
            if (recipients.isEmpty()) return;
            recipient = recipients.getFirst();
        }
        String replyUrl = user == null && invitation != null ? settings.link(invitation.token()) : null;
        boolean removedOrCancelled = "REMOVED".equals(kind) || "CANCELLED".equals(kind);
        var payload = new InterviewMailPayload(recipient.email(), recipient.name(), candidate.title(),
                details.roundName(), details.startsAt(), details.endsAt(), details.mode().name(),
                removedOrCancelled ? null : details.room(), removedOrCancelled ? null : details.meetingUrl(),
                removedOrCancelled ? null : details.preparationInstructions(), kind, replyUrl, reason);
        UUID id = UUID.randomUUID();
        String dedupe = tokens.hash(current.id() + ":" + current.version() + ":" + kind + ":"
                + (user == null ? "candidate" : user) + ":" + (invitation == null ? "none" : invitation.id()));
        if (user != null) {
            outbox.enqueue(id, current, invitation == null ? null : invitation.id(), user, kind, dedupe,
                    crypto.encrypt(id, current.id(), payload));
            return;
        }
        // Task 533 (story S8-01): only the first invitation is the automatic email of the INTERVIEW_INVITATION type.
        // With its automatic sending off it is recorded as SUPPRESSED and not queued; the recruiter informs the
        // candidate. An update or a cancellation always goes out: the candidate may hold the earlier details.
        var message = new EmailMessageLog.Message(EmailTemplateType.INTERVIEW_INVITATION, kind, current.applicationId(),
                current.id(), null, EmailMessageLog.Trigger.AUTOMATIC, recipient.email(), "INTERVIEW_INVITATION:" + dedupe,
                null);
        if ("INVITATION".equals(kind) && !messages.autoSend(EmailTemplateType.INTERVIEW_INVITATION)) {
            messages.suppressed(message);
            return;
        }
        if (outbox.enqueue(id, current, invitation == null ? null : invitation.id(), null, kind, dedupe,
                crypto.encrypt(id, current.id(), payload))) {
            messages.queued(message, EmailMessageLog.Delivery.INTERVIEW_INVITATION, id);
        }
    }

    private record Invitation(UUID id, String token) {
        @Override public String toString() { return "Invitation[redacted]"; }
    }
    private record Candidate(String email, String name, String title) {
        @Override public String toString() { return "Candidate[redacted]"; }
    }
    private record Recipient(String email, String name) {
        @Override public String toString() { return "Recipient[redacted]"; }
    }
}
