package vn.ttcs.recruitment.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.ttcs.recruitment.messaging.EmailMessageLog;
import vn.ttcs.recruitment.messaging.FailedEmailResender;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Task 539: a failed application receipt queued again. V28 keeps the encrypted payload of a FAILED row (only SENT
 * erases it), so the same row goes back to PENDING with a fresh cycle of attempts; the attempt log keeps the earlier
 * ones. Refused when the payload itself was invalid (the tracking code is stored only as a hash, so it cannot be
 * rebuilt), when the tracking credential was revoked or the candidate withdrew (the receipt's links would be useless
 * or must stay dead), or when the application's address is no longer the one the receipt was written for.
 */
@Component
class ApplicationReceiptResender implements FailedEmailResender {
    private final JdbcTemplate jdbc;

    ApplicationReceiptResender(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public EmailMessageLog.Delivery delivery() {
        return EmailMessageLog.Delivery.APPLICATION_CONFIRMATION;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID resend(FailedEmail email) {
        var rows = jdbc.queryForList("""
                SELECT o.status, o.last_failure, a.email, a.stage, a.tracking_revoked_at
                FROM application_email_outbox o JOIN job_applications a ON a.id = o.application_id
                WHERE o.id = ? AND o.application_id = ? FOR UPDATE OF o
                """, email.deliveryId(), email.applicationId());
        if (rows.isEmpty()) throw FailedEmailResender.notPossible("Không còn dữ liệu của thư này để gửi lại.");
        Map<String, Object> row = rows.getFirst();
        if (!"FAILED".equals(row.get("status"))) {
            throw FailedEmailResender.obsolete("Thư không còn ở trạng thái gửi thất bại.");
        }
        if ("PAYLOAD_INVALID".equals(row.get("last_failure"))) {
            throw FailedEmailResender.notPossible("Nội dung thư xác nhận bị lỗi và không thể dựng lại (mã tra cứu chỉ"
                    + " được lưu dạng băm). Vui lòng liên hệ ứng viên trực tiếp.");
        }
        if ("WITHDRAWN".equals(row.get("stage")) || row.get("tracking_revoked_at") != null) {
            throw FailedEmailResender.obsolete("Hồ sơ đã rút hoặc mã tra cứu đã bị thu hồi nên không gửi lại thư xác nhận.");
        }
        if (!Objects.equals(row.get("email"), email.recipientEmail())) {
            throw FailedEmailResender.obsolete("Email của hồ sơ đã thay đổi so với lúc gửi thư.");
        }
        jdbc.update("""
                UPDATE application_email_outbox SET status='PENDING', attempts=0, next_attempt_at=clock_timestamp(),
                    last_failure=NULL, updated_at=clock_timestamp()
                WHERE id=? AND status='FAILED'
                """, email.deliveryId());
        return email.deliveryId();
    }
}
