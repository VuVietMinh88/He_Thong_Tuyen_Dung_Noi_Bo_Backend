package vn.ttcs.recruitment.auth;

import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.notification.NotificationEmailWorker;
import vn.ttcs.recruitment.notification.NotificationEvents;
import vn.ttcs.recruitment.notification.NotificationLink;
import vn.ttcs.recruitment.notification.NotificationType;
import vn.ttcs.recruitment.notification.NotificationWorker;

import java.io.IOException;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 551: each recipient's channel preferences are read at delivery: in-app and email separately, the mandatory
 * approval notifications always on both, the email sent after commit to the recipient's current address.
 */
@TestPropertySource(properties = {"app.notifications.poll-delay-ms=3600000",
        "app.notifications.email-poll-delay-ms=3600000", "app.notifications.email-delivery-enabled=true"})
class NotificationChannelDeliveryIntegrationTest extends ScreeningTestSupport {
    private static final LocalSmtpServer SMTP = smtpServer();
    @Autowired NotificationWorker notifications;
    @Autowired NotificationEmailWorker emails;
    @Autowired NotificationEvents events;

    @DynamicPropertySource static void smtp(DynamicPropertyRegistry values) {
        values.add("spring.mail.host", () -> "127.0.0.1");
        values.add("spring.mail.port", SMTP::port);
        values.add("spring.mail.properties.mail.smtp.auth", () -> false);
        values.add("spring.mail.properties.mail.smtp.starttls.enable", () -> false);
        values.add("spring.mail.properties.mail.smtp.starttls.required", () -> false);
        values.add("spring.mail.properties.mail.smtp.ssl.enable", () -> false);
    }
    @AfterAll static void closeSmtp() throws IOException { SMTP.close(); }
    private static LocalSmtpServer smtpServer() {
        try { return new LocalSmtpServer(); } catch (IOException failure) { throw new IllegalStateException(failure); }
    }

    @BeforeEach void clean() {
        SMTP.reset();
        jdbc.execute("TRUNCATE notifications, notification_events, notification_email_outbox, notification_preferences");
    }

    @Test
    void eachChannelFollowsTheRecipientsOwnChoice() throws Exception {
        prefer(recruiter, "RECRUITER_ASSIGNED", "IN_APP", false);
        prefer(outsider, "RECRUITER_ASSIGNED", "EMAIL", false);
        publish(NotificationType.RECRUITER_ASSIGNED, requisition, List.of(recruiter));
        jdbc.update("INSERT INTO requisition_recruiters(requisition_id,recruiter_id,assignment_role,assigned_by,assigned_at)"
                + " VALUES (?,?,'SUPPORTING',?,?)", requisition, outsider, hr, Timestamp.from(ApplicationTestSupport.NOW));
        publish(NotificationType.RECRUITER_ASSIGNED, requisition, List.of(outsider));
        drain();
        assertThat(jdbc.queryForList("SELECT recipient_id FROM notifications", UUID.class)).containsExactly(outsider);
        assertThat(jdbc.queryForList("SELECT recipient_id FROM notification_email_outbox", UUID.class)).containsExactly(recruiter);

        assertThat(emails.processNext()).isTrue();
        assertThat(emails.processNext()).isFalse();
        MimeMessage mail = SMTP.messages().getFirst();
        assertThat(mail.getAllRecipients()[0].toString()).isEqualTo("recruiter@example.test");
        assertThat(mail.getSubject()).isEqualTo("[Thông báo] Bạn được phân công vị trí mới");
        assertThat(CandidateEmailTemplateDeliveryIntegrationTest.plain(mail)).contains("Xin chào Recruiter A,",
                "Bạn được phân công tuyển dụng", "đăng nhập");
        assertThat(jdbc.queryForObject("SELECT status FROM notification_email_outbox", String.class)).isEqualTo("SENT");
    }

    @Test
    void aCopyKeepsIdsOnlyAndIsCancelledWhenTooOldTurnedOffOrNoLongerPlanned() throws Exception {
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns"
                + " WHERE table_name = 'notification_email_outbox'", String.class)).doesNotContain("title", "body");
        jdbc.update("INSERT INTO requisition_recruiters(requisition_id,recruiter_id,assignment_role,assigned_by,assigned_at)"
                + " VALUES (?,?,'SUPPORTING',?,?)", requisition, outsider, hr, Timestamp.from(ApplicationTestSupport.NOW));
        publish(NotificationType.RECRUITER_ASSIGNED, requisition, List.of(recruiter));
        publish(NotificationType.RECRUITER_ASSIGNED, requisition, List.of(outsider));
        drain();
        UUID old = jdbc.queryForObject("SELECT id FROM notification_email_outbox WHERE recipient_id=?", UUID.class, recruiter);
        publish(NotificationType.RECRUITER_ASSIGNED, requisition, List.of(recruiter));
        drain();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_email_outbox", Long.class)).isEqualTo(3);

        // The first copy waited longer than the maximum age; the recruiter then turned the email channel off; the
        // supporting recruiter was removed from the requisition. Nothing is sent.
        jdbc.update("UPDATE notification_email_outbox SET created_at = clock_timestamp() - INTERVAL '25 hours' WHERE id=?", old);
        prefer(recruiter, "RECRUITER_ASSIGNED", "EMAIL", false);
        jdbc.update("DELETE FROM requisition_recruiters WHERE requisition_id=? AND recruiter_id=?", requisition, outsider);
        for (int i = 0; i < 5 && emails.processNext(); i++) { /* drain */ }
        assertThat(SMTP.messages()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT last_failure FROM notification_email_outbox WHERE id=?", String.class, old))
                .isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT last_failure FROM notification_email_outbox WHERE recipient_id=? AND id<>?",
                String.class, recruiter, old)).isEqualTo("PREFERENCE_OFF");
        assertThat(jdbc.queryForObject("SELECT last_failure FROM notification_email_outbox WHERE recipient_id=?",
                String.class, outsider)).isEqualTo("OBSOLETE");
        assertThat(jdbc.queryForList("SELECT DISTINCT status FROM notification_email_outbox", String.class))
                .containsExactly("CANCELLED");
    }

    @Test
    void approvalNotificationsAlwaysUseBothChannels() throws Exception {
        UUID offer = UUID.randomUUID();
        jdbc.update("UPDATE job_applications SET stage='OFFER' WHERE id=?", first.applicationId());
        jdbc.update("""
                INSERT INTO offers(id,application_id,candidate_id,position_id,candidate_name,email,position_title,
                    salary,allowance,start_date,response_deadline,band_min,band_max,outside_band,status,version,
                    approval_round,created_by,submitted_by,hr_approver_id,created_at,updated_at)
                SELECT ?,a.id,ca.candidate_id,r.position_id,a.full_name,a.email,p.name,
                    20000000,0,DATE '2026-12-01',?,10000000,30000000,false,'PENDING_HR',0,1,?,?,?,?,?
                FROM job_applications a JOIN candidate_applications ca ON ca.application_id=a.id
                JOIN recruitment_requisitions r ON r.id=a.requisition_id JOIN positions p ON p.id=r.position_id WHERE a.id=?
                """, offer, Timestamp.from(ApplicationTestSupport.NOW.plusSeconds(86400 * 10)), recruiter, recruiter, hr,
                Timestamp.from(ApplicationTestSupport.NOW), Timestamp.from(ApplicationTestSupport.NOW), first.applicationId());
        new TransactionTemplate(transactions).executeWithoutResult(status ->
                events.publish(NotificationType.APPROVAL_PENDING, NotificationLink.Type.OFFER, offer));
        drain();
        assertThat(jdbc.queryForList("SELECT recipient_id FROM notifications", UUID.class)).containsExactly(hr);
        assertThat(jdbc.queryForList("SELECT recipient_id FROM notification_email_outbox", UUID.class)).containsExactly(hr);
    }

    @Test
    void aLockedRecipientIsNotMailedAndATemporaryFailureIsRetried() throws Exception {
        publish(NotificationType.RECRUITER_ASSIGNED, requisition, List.of(recruiter));
        drain();
        SMTP.rejectDelivery(true);
        assertThat(emails.processNext()).isTrue();
        assertThat(jdbc.queryForMap("SELECT status, last_failure FROM notification_email_outbox"))
                .containsEntry("status", "RETRY_WAIT").containsEntry("last_failure", "SMTP_TEMPORARY");
        SMTP.rejectDelivery(false);
        jdbc.update("UPDATE user_accounts SET admin_locked_at=?, admin_lock_reason='Nghỉ', admin_locked_by=? WHERE id=?",
                Timestamp.from(ApplicationTestSupport.NOW), hr, recruiter);
        jdbc.update("UPDATE notification_email_outbox SET next_attempt_at = clock_timestamp() - INTERVAL '1 second'");
        assertThat(emails.processNext()).isTrue();
        assertThat(jdbc.queryForMap("SELECT status, last_failure FROM notification_email_outbox"))
                .containsEntry("status", "CANCELLED").containsEntry("last_failure", "RECIPIENT_UNAVAILABLE");
        assertThat(SMTP.messages()).isEmpty();
    }

    private void publish(NotificationType type, UUID requisitionId, List<UUID> recipients) {
        new TransactionTemplate(transactions).executeWithoutResult(status ->
                events.publish(type, NotificationLink.Type.REQUISITION, requisitionId, recipients));
    }

    private void prefer(UUID user, String type, String channel, boolean enabled) {
        jdbc.update("INSERT INTO notification_preferences(user_id, notification_type, channel, enabled, updated_at)"
                + " VALUES (?,?,?,?,now())", user, type, channel, enabled);
    }

    private void drain() {
        for (int i = 0; i < 20 && notifications.processNext(); i++) { /* drain */ }
    }
}
