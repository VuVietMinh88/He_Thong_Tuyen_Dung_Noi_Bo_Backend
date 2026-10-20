package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.notification.NotificationLink;
import vn.ttcs.recruitment.notification.NotificationRepository;
import vn.ttcs.recruitment.notification.NotificationType;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Task 543: notifications stored per recipient, with a read state of their own and one row per key. */
class NotificationStorageIntegrationTest extends ScreeningTestSupport {
    @Autowired NotificationRepository notifications;
    TransactionTemplate transaction;

    @BeforeEach void clean() {
        transaction = new TransactionTemplate(transactions);
        jdbc.execute("TRUNCATE notifications");
    }

    @Test
    void eachRecipientHasItsOwnRowAndReadStateAndAKeyIsStoredOnce() {
        Instant at = ApplicationTestSupport.NOW;
        assertThat(insert(recruiter, "APPROVAL_PENDING:REQUISITION:x:1:1", at)).isTrue();
        assertThat(insert(hr, "APPROVAL_PENDING:REQUISITION:x:1:1", at)).isTrue();
        assertThat(insert(recruiter, "APPROVAL_PENDING:REQUISITION:x:1:1", at.plusSeconds(5))).isFalse();
        assertThat(notifications.count(recruiter, true)).isEqualTo(1);
        UUID mine = notifications.page(recruiter, false, 0, 10).getFirst().id();

        assertThat(read(hr, mine, at.plusSeconds(60))).isFalse();
        assertThat(read(recruiter, mine, at.plusSeconds(60))).isTrue();
        assertThat(read(recruiter, mine, at.plusSeconds(120))).isTrue();
        assertThat(notifications.find(recruiter, mine).orElseThrow().readAt()).isEqualTo(at.plusSeconds(60));
        assertThat(notifications.find(hr, mine)).isEmpty();
        assertThat(notifications.count(recruiter, true)).isZero();
        assertThat(notifications.count(hr, true)).isEqualTo(1);
        int marked = transaction.execute(status -> notifications.markAllRead(hr, at.plusSeconds(10)));
        assertThat(marked).isEqualTo(1);
        assertThat(notifications.count(hr, true)).isZero();
    }

    @Test
    void storedTextIsOneBoundedLineAndLinksAreEntityReferences() {
        String title = "Tiêu đề\r\n" + "x".repeat(300);
        transaction.executeWithoutResult(status -> notifications.insert(new NotificationRepository.New(recruiter,
                NotificationType.INTERVIEW_INVITED, title, "Nội dung", NotificationLink.interview(UUID.randomUUID(),
                first.applicationId()), "k", ApplicationTestSupport.NOW)));
        var stored = notifications.page(recruiter, false, 0, 1).getFirst();
        assertThat(stored.title()).hasSize(200).doesNotContain("\n", "\r").endsWith("…");
        assertThat(stored.link().type()).isEqualTo(NotificationLink.Type.INTERVIEW);
        assertThat(stored.link().parentId()).isEqualTo(first.applicationId());
        for (String sql : new String[] {
                "UPDATE notifications SET link_type='URL'",
                "UPDATE notifications SET parent_id=NULL",
                "UPDATE notifications SET notification_type='OTHER'",
                "UPDATE notifications SET read_at = created_at - INTERVAL '1 second'"}) {
            assertThatThrownBy(() -> jdbc.update(sql)).as(sql).isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Test
    void notificationsGoWithTheirRecipientsAccount() {
        UUID temp = applications.account("Temp", "temp-notify@example.test", Role.RECRUITER);
        insert(temp, "k1", ApplicationTestSupport.NOW);
        jdbc.update("DELETE FROM user_roles WHERE user_id=?", temp);
        jdbc.update("DELETE FROM user_accounts WHERE id=?", temp);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications", Long.class)).isZero();
    }

    private boolean read(UUID recipient, UUID id, Instant at) {
        return Boolean.TRUE.equals(transaction.execute(status -> notifications.markRead(recipient, id, at)));
    }

    private boolean insert(UUID recipient, String key, Instant at) {
        return Boolean.TRUE.equals(transaction.execute(status -> notifications.insert(new NotificationRepository.New(
                recipient, NotificationType.APPROVAL_PENDING, "Yêu cầu chờ duyệt", "Nội dung",
                NotificationLink.requisition(requisition), key, at))));
    }
}
