package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import vn.ttcs.recruitment.notification.NotificationWorker;
import vn.ttcs.recruitment.sla.SlaMonitor;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 420: the assigned recruiter is reminded once per application, stage, day and recipient, in-app and by email
 * according to preferences; reruns and concurrent runs of the same day remind nobody twice; only eligible recruiters of
 * recruiting requisitions are reminded.
 */
// Email copies are kept only while notification email delivery is on (task 551); the worker itself never polls here.
@TestPropertySource(properties = {"app.sla.cron=-", "app.notifications.poll-delay-ms=3600000",
        "app.notifications.email-poll-delay-ms=3600000", "app.notifications.email-delivery-enabled=true"})
class SlaReminderIntegrationTest extends ScreeningTestSupport {
    @Autowired SlaMonitor monitor;
    @Autowired NotificationWorker notifications;

    @BeforeEach void thresholds() {
        jdbc.update("UPDATE stage_sla_thresholds SET max_days = NULL");
        jdbc.update("UPDATE stage_sla_thresholds SET max_days = 3 WHERE stage = 'NEW'");
        jdbc.execute("TRUNCATE application_sla_flags, sla_reminders, notification_events, notifications, notification_email_outbox");
        clock.set(ApplicationTestSupport.NOW.plus(Duration.ofDays(4)));
    }

    @Test
    void thePrimaryRecruiterIsRemindedOncePerDayAndAgainTheNextDay() {
        assertThat(monitor.run().reminders()).isEqualTo(2);
        assertThat(monitor.run().reminders()).isZero();
        assertThat(jdbc.queryForList("SELECT recipient_id FROM sla_reminders WHERE application_id=?", UUID.class,
                first.applicationId())).containsExactly(recruiter);
        assertThat(jdbc.queryForList("SELECT recipient_id FROM sla_reminders WHERE application_id=?", UUID.class,
                other.applicationId())).containsExactly(outsider);
        drain();
        assertThat(jdbc.queryForList("SELECT recipient_id FROM notifications WHERE notification_type='SLA_OVERDUE'"
                + " AND link_id=?", UUID.class, first.applicationId())).containsExactly(recruiter);
        assertThat(jdbc.queryForObject("SELECT body FROM notifications WHERE recipient_id=?", String.class, recruiter))
                .contains(ApplicationTestSupport.FULL_NAME, "Hồ sơ mới", "3 ngày");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_email_outbox WHERE recipient_id=?", Long.class,
                recruiter)).isEqualTo(1);

        clock.set(ApplicationTestSupport.NOW.plus(Duration.ofDays(5)));
        assertThat(monitor.run().reminders()).isEqualTo(2);
        drain();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE recipient_id=?", Long.class, recruiter))
                .isEqualTo(2);
    }

    @Test
    void preferencesTurnOffAChannelAndAMovedApplicationIsNoLongerReminded() {
        jdbc.update("""
                INSERT INTO notification_preferences(user_id, notification_type, channel, enabled, updated_at)
                VALUES (?, 'SLA_OVERDUE', 'EMAIL', false, now()), (?, 'SLA_OVERDUE', 'IN_APP', false, now())
                """, recruiter, outsider);
        monitor.run();
        drain();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE recipient_id=?", Long.class, recruiter)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_email_outbox WHERE recipient_id=?", Long.class, recruiter)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE recipient_id=?", Long.class, outsider)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_email_outbox WHERE recipient_id=?", Long.class, outsider)).isEqualTo(1);

        jdbc.update("UPDATE job_applications SET stage='REJECTED' WHERE id=?", first.applicationId());
        clock.set(ApplicationTestSupport.NOW.plus(Duration.ofDays(6)));
        monitor.run();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sla_reminders WHERE application_id=?", Long.class,
                first.applicationId())).isEqualTo(1);
    }

    @Test
    void withoutAPrimaryTheSupportingRecruitersAreRemindedAndConcurrentRunsRemindOnce() throws Exception {
        UUID support = applications.account("Support", "support@example.test", vn.ttcs.recruitment.account.Role.RECRUITER);
        jdbc.update("DELETE FROM requisition_recruiters WHERE requisition_id=?", requisition);
        applications.assign(requisition, support, "SUPPORTING", hr);
        List<Callable<SlaMonitor.Result>> runs = List.of(monitor::run, monitor::run, monitor::run);
        int reminders = 0;
        try (var executor = Executors.newFixedThreadPool(3)) {
            for (var result : executor.invokeAll(runs)) reminders += result.get().reminders();
        }
        assertThat(reminders).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT recipient_id FROM sla_reminders WHERE application_id=?", UUID.class,
                first.applicationId())).containsExactly(support);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_events", Long.class)).isEqualTo(2);
    }

    @Test
    void aRequisitionThatStoppedRecruitingRemindsNobody() {
        jdbc.update("UPDATE recruitment_requisitions SET status='FILLED' WHERE id=?", otherRequisition);
        assertThat(monitor.run().reminders()).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT application_id FROM sla_reminders", UUID.class))
                .containsExactly(first.applicationId());
    }

    @Test
    void aDisabledOrUnauthorizedPrimaryFallsBackToTheEligibleSupportingRecruiters() {
        UUID support = applications.account("Support", "support@example.test", vn.ttcs.recruitment.account.Role.RECRUITER);
        applications.assign(requisition, support, "SUPPORTING", hr);
        // The primary recruiter's account is disabled: the supporting recruiter is reminded instead.
        jdbc.update("UPDATE user_accounts SET enabled=false WHERE id=?", recruiter);
        monitor.run();
        assertThat(recipients(first.applicationId())).containsExactly(support);

        // The next day the primary is enabled again but no longer holds a role that reads candidates.
        jdbc.update("UPDATE user_accounts SET enabled=true WHERE id=?", recruiter);
        jdbc.update("DELETE FROM user_roles WHERE user_id=?", recruiter);
        clock.set(ApplicationTestSupport.NOW.plus(Duration.ofDays(5)));
        monitor.run();
        assertThat(jdbc.queryForList("SELECT recipient_id FROM sla_reminders WHERE application_id=? AND reminder_date=?",
                UUID.class, first.applicationId(), java.sql.Date.valueOf("2026-10-20"))).containsExactly(support);

        // A reminder whose recipient loses access before it is delivered notifies nobody.
        jdbc.update("DELETE FROM user_roles WHERE user_id=?", support);
        drain();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE recipient_id IN (?, ?)", Long.class,
                support, recruiter)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_email_outbox WHERE recipient_id IN (?, ?)",
                Long.class, support, recruiter)).isZero();
    }

    private List<UUID> recipients(UUID application) {
        return jdbc.queryForList("SELECT recipient_id FROM sla_reminders WHERE application_id=?", UUID.class, application);
    }

    private void drain() {
        for (int i = 0; i < 20 && notifications.processNext(); i++) { /* drain */ }
    }
}
