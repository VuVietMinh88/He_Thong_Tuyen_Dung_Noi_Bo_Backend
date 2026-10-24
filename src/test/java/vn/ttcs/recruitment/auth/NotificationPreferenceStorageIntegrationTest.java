package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.notification.NotificationPreferences;
import vn.ttcs.recruitment.notification.NotificationPreferences.Channel;
import vn.ttcs.recruitment.notification.NotificationType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Task 549: per user, type and channel choices; missing means on; the mandatory type can never be off. */
class NotificationPreferenceStorageIntegrationTest extends ScreeningTestSupport {
    @Autowired NotificationPreferences preferences;

    @BeforeEach void clean() { jdbc.execute("TRUNCATE notification_preferences"); }

    @Test
    void choicesAreStoredPerUserTypeAndChannelAndDefaultToOn() {
        assertThat(preferences.enabled(recruiter, NotificationType.RECRUITER_ASSIGNED, Channel.EMAIL)).isTrue();
        store(recruiter, NotificationType.RECRUITER_ASSIGNED, Channel.EMAIL, false);
        assertThat(preferences.enabled(recruiter, NotificationType.RECRUITER_ASSIGNED, Channel.EMAIL)).isFalse();
        assertThat(preferences.enabled(recruiter, NotificationType.RECRUITER_ASSIGNED, Channel.IN_APP)).isTrue();
        assertThat(preferences.enabled(hr, NotificationType.RECRUITER_ASSIGNED, Channel.EMAIL)).isTrue();
        store(recruiter, NotificationType.RECRUITER_ASSIGNED, Channel.EMAIL, true);
        assertThat(preferences.enabled(recruiter, NotificationType.RECRUITER_ASSIGNED, Channel.EMAIL)).isTrue();
        var mine = preferences.of(recruiter);
        assertThat(mine).hasSize(NotificationType.values().length);
        assertThat(mine.get(NotificationType.APPROVAL_PENDING)).isEqualTo(new NotificationPreferences.Choice(true, true));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_preferences", Long.class)).isEqualTo(1);
    }

    @Test
    void theMandatoryTypeCannotBeTurnedOffInCodeOrInTheDatabase() {
        assertThatThrownBy(() -> store(recruiter, NotificationType.APPROVAL_PENDING, Channel.IN_APP, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO notification_preferences(user_id, notification_type, channel, enabled, updated_at)
                VALUES (?, 'APPROVAL_PENDING', 'EMAIL', false, now())
                """, recruiter)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO notification_preferences(user_id, notification_type, channel, enabled, updated_at)
                VALUES (?, 'SLA_OVERDUE', 'SMS', false, now())
                """, recruiter)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(NotificationPreferences.enabled(NotificationType.APPROVAL_PENDING, false)).isTrue();
        assertThat(NotificationPreferences.enabled(NotificationType.SLA_OVERDUE, null)).isTrue();
        assertThat(NotificationPreferences.enabled(NotificationType.SLA_OVERDUE, false)).isFalse();
    }

    @Test
    void choicesGoWithTheirAccount() {
        UUID temp = applications.account("Temp", "temp-prefs@example.test", Role.RECRUITER);
        store(temp, NotificationType.SLA_OVERDUE, Channel.IN_APP, false);
        jdbc.update("DELETE FROM user_roles WHERE user_id=?", temp);
        jdbc.update("DELETE FROM user_accounts WHERE id=?", temp);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_preferences", Long.class)).isZero();
    }

    private void store(UUID user, NotificationType type, Channel channel, boolean enabled) {
        new TransactionTemplate(transactions).executeWithoutResult(status ->
                preferences.store(user, type, channel, enabled, ApplicationTestSupport.NOW));
    }
}
