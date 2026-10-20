package vn.ttcs.recruitment.notification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Tasks 544/551 (story S8-04, "Đọc tùy chọn trước gửi qua từng kênh, luôn giữ luồng thông báo phê duyệt bắt buộc"):
 * delivers one planned notification in NotificationWorker's fenced transaction, channel by channel, reading the
 * recipient's preference for that type and channel right before: the in-app inbox (notifications) and the email copy
 * (notification_email_outbox, ids only, rebuilt and sent later by NotificationEmailWorker). A mandatory type goes to
 * both channels whatever is stored. The same recipient and key twice store one row per channel. While email delivery
 * is switched off (NOTIFICATION_EMAIL_DELIVERY_ENABLED=false, the default) no email copy is kept at all, so switching
 * it on later never sends a backlog of old notifications.
 */
@Component
public class NotificationDispatch {
    private final NotificationRepository notifications;
    private final NotificationPreferences preferences;
    private final NotificationEmailOutbox emails;
    private final boolean emailDelivery;

    public NotificationDispatch(NotificationRepository notifications, NotificationPreferences preferences,
                                NotificationEmailOutbox emails,
                                @Value("${app.notifications.email-delivery-enabled:false}") boolean emailDelivery) {
        this.notifications = notifications;
        this.preferences = preferences;
        this.emails = emails;
        this.emailDelivery = emailDelivery;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void deliver(UUID eventId, NotificationPlanner.Planned planned, Instant at) {
        var notification = new NotificationRepository.New(planned.recipient(), planned.type(), planned.title(),
                planned.body(), planned.link(), planned.dedupeKey(), at);
        if (preferences.enabled(planned.recipient(), planned.type(), NotificationPreferences.Channel.IN_APP)) {
            notifications.insert(notification);
        }
        if (emailDelivery
                && preferences.enabled(planned.recipient(), planned.type(), NotificationPreferences.Channel.EMAIL)) {
            emails.enqueue(eventId, planned.recipient(), planned.type(), planned.dedupeKey());
        }
    }
}
