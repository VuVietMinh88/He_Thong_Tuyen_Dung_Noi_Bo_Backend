package vn.ttcs.recruitment.notification;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Task 544: delivers one planned notification to its recipient's in-app inbox, in the worker's fenced transaction.
 * The same recipient and key twice store one notification.
 */
@Component
public class NotificationDispatch {
    private final NotificationRepository notifications;

    public NotificationDispatch(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void deliver(NotificationPlanner.Planned planned, Instant at) {
        notifications.insert(new NotificationRepository.New(planned.recipient(), planned.type(), planned.title(),
                planned.body(), planned.link(), planned.dedupeKey(), at));
    }
}
