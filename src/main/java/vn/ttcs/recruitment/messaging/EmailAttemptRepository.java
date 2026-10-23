package vn.ttcs.recruitment.messaging;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Task 537 (story S8-02): reads the immutable attempt log of email_attempts (V66_2). Rows are written only by the
 * database trigger on the outboxes, in the worker's fenced transaction, and never changed; this class has no write.
 */
@Repository
public class EmailAttemptRepository {
    private final JdbcTemplate jdbc;

    public EmailAttemptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One attempt: when it ended, the template type and the version current then, the recipient, the result (SENT,
     * RETRY_SCHEDULED, FAILED, CANCELLED, LEASE_EXPIRED) and the safe error code (null when sent).
     */
    public record Attempt(UUID id, int number, String delivery, UUID deliveryId, String templateType,
                          Long templateVersion, String recipientEmail, String result, String errorCode,
                          Instant attemptedAt) {
        @Override public String toString() { return "EmailAttempt[" + number + ", " + result + ", recipient redacted]"; }
    }

    public List<Attempt> forMessage(UUID messageId) {
        return jdbc.query("""
                SELECT id, attempt_number, delivery, delivery_id, template_type, template_version, recipient_email,
                    result, error_code, attempted_at
                FROM email_attempts WHERE message_id = ? ORDER BY attempt_number
                """, (row, number) -> new Attempt(row.getObject("id", UUID.class), row.getInt("attempt_number"),
                row.getString("delivery"), row.getObject("delivery_id", UUID.class), row.getString("template_type"),
                row.getObject("template_version") == null ? null : row.getLong("template_version"),
                row.getString("recipient_email"), row.getString("result"), row.getString("error_code"),
                row.getTimestamp("attempted_at").toInstant()), messageId);
    }
}
