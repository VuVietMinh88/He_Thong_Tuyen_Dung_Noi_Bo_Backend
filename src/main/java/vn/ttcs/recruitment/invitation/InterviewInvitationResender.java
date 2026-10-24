package vn.ttcs.recruitment.invitation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.ttcs.recruitment.application.ApplicationStage;
import vn.ttcs.recruitment.auth.passwordreset.ResetTokenGenerator;
import vn.ttcs.recruitment.messaging.EmailMessageLog;
import vn.ttcs.recruitment.messaging.FailedEmailResender;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Task 539: a candidate's failed interview email queued again. V58 erases the payload of a FAILED row and its reply
 * capability must not be revived, so the email is rebuilt from the interview as it is now, in a new outbox row, and only
 * while it is still the same appointment: for an invitation or an update the interview must still be scheduled at the
 * version of the failed email, in the future, for an active application of an approved requisition, and the candidate
 * must not have answered the current invitation (then the email evidently arrived). The current invitation is revoked
 * and a new one issued, so only the link of the new email works; staff emails of the interview are left alone. A
 * failed cancellation is rebuilt only while the interview is still cancelled, without any link.
 */
@Component
class InterviewInvitationResender implements FailedEmailResender {
    private final JdbcTemplate jdbc;
    private final InterviewInvitationSettings settings;
    private final InterviewInvitationCrypto crypto;
    private final InterviewInvitationOutboxRepository outbox;
    private final ResetTokenGenerator tokens;
    private final Clock clock;

    InterviewInvitationResender(JdbcTemplate jdbc, InterviewInvitationSettings settings,
                                InterviewInvitationCrypto crypto, InterviewInvitationOutboxRepository outbox,
                                ResetTokenGenerator tokens, Clock clock) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.crypto = crypto;
        this.outbox = outbox;
        this.tokens = tokens;
        this.clock = clock;
    }

    @Override
    public EmailMessageLog.Delivery delivery() {
        return EmailMessageLog.Delivery.INTERVIEW_INVITATION;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID resend(FailedEmail email) {
        settings.requireReady();
        var interviews = jdbc.queryForList("""
                SELECT i.round_name, i.starts_at, i.ends_at, i.mode, i.room, i.meeting_url, i.status, i.version,
                    i.preparation_instructions, a.email, a.full_name, a.posting_title, a.stage,
                    r.status AS requisition_status
                FROM interview_sessions i JOIN job_applications a ON a.id = i.application_id
                JOIN recruitment_requisitions r ON r.id = a.requisition_id
                WHERE i.id = ? AND i.application_id = ? FOR UPDATE OF i
                """, email.interviewId(), email.applicationId());
        var failed = jdbc.queryForList("""
                SELECT status, kind, schedule_version FROM interview_invitation_outbox
                WHERE id = ? AND interview_id = ? AND recipient_user_id IS NULL FOR UPDATE
                """, email.deliveryId(), email.interviewId());
        if (interviews.isEmpty() || failed.isEmpty()) throw FailedEmailResender.notPossible("Không còn lịch phỏng vấn của thư này.");
        Map<String, Object> current = interviews.getFirst();
        Map<String, Object> row = failed.getFirst();
        if (!"FAILED".equals(row.get("status"))) throw FailedEmailResender.obsolete("Thư không còn ở trạng thái gửi thất bại.");
        if (!Objects.equals(current.get("email"), email.recipientEmail())) {
            throw FailedEmailResender.obsolete("Email của hồ sơ đã thay đổi so với lúc gửi thư.");
        }
        String kind = (String) row.get("kind");
        long version = ((Number) current.get("version")).longValue();
        if (version != ((Number) row.get("schedule_version")).longValue()) {
            throw FailedEmailResender.obsolete("Lịch phỏng vấn đã thay đổi sau thư này; ứng viên nhận thư của lịch mới.");
        }
        Instant now = clock.instant();
        Instant startsAt = ((Timestamp) current.get("starts_at")).toInstant();
        Instant endsAt = ((Timestamp) current.get("ends_at")).toInstant();
        InterviewMailPayload payload;
        UUID invitation = null;
        if ("CANCELLED".equals(kind)) {
            if (!"CANCELLED".equals(current.get("status"))) throw FailedEmailResender.obsolete("Lịch phỏng vấn không còn ở trạng thái hủy.");
            String reason = jdbc.query("""
                    SELECT reason FROM interview_events WHERE interview_id = ? AND event_type = 'INTERVIEW_CANCELLED'
                    ORDER BY occurred_at DESC, id DESC LIMIT 1
                    """, (result, number) -> result.getString(1), email.interviewId()).stream().findFirst().orElse(null);
            payload = new InterviewMailPayload((String) current.get("email"), (String) current.get("full_name"),
                    (String) current.get("posting_title"), (String) current.get("round_name"), startsAt, endsAt,
                    (String) current.get("mode"), null, null, null, kind, null, reason);
        } else {
            if (!"SCHEDULED".equals(current.get("status")) || !startsAt.isAfter(now)
                    || !"APPROVED".equals(current.get("requisition_status"))
                    || !ApplicationStage.valueOf((String) current.get("stage")).active()) {
                throw FailedEmailResender.obsolete("Buổi phỏng vấn đã diễn ra, bị hủy hoặc hồ sơ không còn hoạt động.");
            }
            Boolean answered = jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM interview_invitations
                        WHERE interview_id = ? AND revoked_at IS NULL AND status <> 'PENDING')
                    """, Boolean.class, email.interviewId());
            if (Boolean.TRUE.equals(answered)) {
                throw FailedEmailResender.obsolete("Ứng viên đã phản hồi lời mời hiện tại nên thư đã đến nơi.");
            }
            Timestamp at = Timestamp.from(now);
            jdbc.update("UPDATE interview_invitations SET revoked_at = ? WHERE interview_id = ? AND revoked_at IS NULL",
                    at, email.interviewId());
            Instant expires = now.plus(Duration.ofDays(7));
            if (startsAt.isBefore(expires)) expires = startsAt;
            invitation = UUID.randomUUID();
            String token = tokens.create();
            jdbc.update("""
                    INSERT INTO interview_invitations(id,interview_id,application_id,schedule_version,token_hash,
                        expires_at,created_at) VALUES (?,?,?,?,?,?,?)
                    """, invitation, email.interviewId(), email.applicationId(), version, tokens.hash(token),
                    Timestamp.from(expires), at);
            payload = new InterviewMailPayload((String) current.get("email"), (String) current.get("full_name"),
                    (String) current.get("posting_title"), (String) current.get("round_name"), startsAt, endsAt,
                    (String) current.get("mode"), (String) current.get("room"), (String) current.get("meeting_url"),
                    (String) current.get("preparation_instructions"), kind, settings.link(token), null);
        }
        UUID id = UUID.randomUUID();
        String dedupe = tokens.hash("retry:" + email.messageId() + ":" + id);
        outbox.enqueue(id, email.interviewId(), email.applicationId(), version, invitation, null, kind, dedupe,
                crypto.encrypt(id, email.interviewId(), payload));
        return id;
    }
}
