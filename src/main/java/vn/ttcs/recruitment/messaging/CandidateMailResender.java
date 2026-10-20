package vn.ttcs.recruitment.messaging;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Task 539: a failed rejection letter or thank-you queued again. The queue keeps no payload, so nothing is lost when a
 * row fails: the same row goes back to PENDING with a fresh cycle of attempts, provided the email still makes sense
 * now (CandidateMailPolicy: still rejected, interview still there and application still active, same address). A
 * failed rejection letter is not retried while another rejection letter of the application is queued, being sent or
 * sent (for example the automatic letter of a later rejection after the application was reopened): 409
 * EMAIL_RETRY_OBSOLETE, never a second live letter (email_messages_one_rejection_idx). The retry service holds the
 * application row lock that every writer of a rejection letter takes first, so the check sees every committed letter.
 */
@Component
class CandidateMailResender implements FailedEmailResender {
    private final JdbcTemplate jdbc;
    private final EmailMessageLog messages;

    CandidateMailResender(JdbcTemplate jdbc, EmailMessageLog messages) {
        this.jdbc = jdbc;
        this.messages = messages;
    }

    @Override
    public EmailMessageLog.Delivery delivery() {
        return EmailMessageLog.Delivery.CANDIDATE_MAIL;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID resend(FailedEmail email) {
        var status = jdbc.queryForList("SELECT status FROM candidate_mail_outbox WHERE id = ? AND message_id = ? FOR UPDATE",
                String.class, email.deliveryId(), email.messageId());
        if (status.isEmpty()) throw FailedEmailResender.notPossible("Không còn dữ liệu của thư này để gửi lại.");
        if (!"FAILED".equals(status.getFirst())) throw FailedEmailResender.obsolete("Thư không còn ở trạng thái gửi thất bại.");
        var content = jdbc.query("""
                SELECT a.email, a.full_name, a.posting_title, a.stage, i.starts_at
                FROM job_applications a
                LEFT JOIN interview_sessions i ON i.id = ? AND i.application_id = a.id
                WHERE a.id = ?
                """, (row, number) -> new CandidateMailOutboxRepository.Content(email.type(), email.recipientEmail(),
                row.getString("email"), row.getString("full_name"), row.getString("posting_title"), row.getString("stage"),
                row.getTimestamp("starts_at") == null ? null : email.interviewId(),
                row.getTimestamp("starts_at") == null ? null : row.getTimestamp("starts_at").toInstant()),
                email.interviewId(), email.applicationId()).stream().findFirst();
        // Not Optional.map: a null reason (the email may go) must not turn into "no content".
        String unavailable = content.isEmpty() ? "OBSOLETE" : CandidateMailPolicy.unavailableReason(content.get());
        if (unavailable != null) {
            throw FailedEmailResender.obsolete("RECIPIENT_UNAVAILABLE".equals(unavailable)
                    ? "Email của hồ sơ đã thay đổi so với lúc gửi thư."
                    : "Thư không còn phù hợp với trạng thái hiện tại của hồ sơ.");
        }
        if (email.type() == EmailTemplateType.REJECTION
                && messages.liveRejection(email.applicationId(), email.messageId())) {
            throw FailedEmailResender.obsolete("Hồ sơ đã có một thư từ chối khác đang chờ gửi, đang gửi hoặc đã gửi.");
        }
        jdbc.update("""
                UPDATE candidate_mail_outbox SET status='PENDING', attempts=0, next_attempt_at=clock_timestamp(),
                    last_failure=NULL, updated_at=clock_timestamp()
                WHERE id=? AND status='FAILED'
                """, email.deliveryId());
        return email.deliveryId();
    }
}
