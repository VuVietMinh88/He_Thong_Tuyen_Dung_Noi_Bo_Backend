package vn.ttcs.recruitment.notification;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.messaging.MessagingActors;
import vn.ttcs.recruitment.messaging.MessagingTransactions;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Task 545 (story S8-03, "Biểu tượng chuông hiển thị số thông báo chưa đọc; Bấm vào thông báo mở đúng màn hình liên
 * quan"): the caller's own inbox. The recipient is always the token's account, never a parameter, and every query
 * names it, so another user's notification is simply not found (404, which also does not tell that it exists).
 * Every internal account has an inbox (SELF_PROFILE_READ to read, SELF_PROFILE_WRITE to mark read), whatever its
 * other permissions: an approver holds no NOTIFICATIONS permission yet must see what waits for its approval.
 */
@Service
public class NotificationService {
    static final int MAX_PAGE_SIZE = 100;
    private final NotificationRepository notifications;
    private final MessagingActors actors;
    private final MessagingTransactions transactions;
    private final Clock clock;

    public NotificationService(NotificationRepository notifications, MessagingActors actors,
                               MessagingTransactions transactions, Clock clock) {
        this.notifications = notifications;
        this.actors = actors;
        this.transactions = transactions;
        this.clock = clock;
    }

    public NotificationViews.Page inbox(Jwt jwt, boolean unreadOnly, int page, int size) {
        UUID me = reader(jwt);
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE || (long) page * size > Integer.MAX_VALUE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Trang hoặc số lượng thông báo không hợp lệ.",
                    Map.of("size", "Kích thước trang từ 1 đến " + MAX_PAGE_SIZE + "."));
        }
        return transactions.read(() -> {
            List<NotificationViews.Item> items = notifications.page(me, unreadOnly, page, size).stream()
                    .map(NotificationViews.Item::of).toList();
            long total = notifications.count(me, unreadOnly);
            long unread = unreadOnly ? total : notifications.count(me, true);
            return new NotificationViews.Page(items, page, size, total, (int) ((total + size - 1) / size), unread);
        });
    }

    public NotificationViews.UnreadCount unreadCount(Jwt jwt) {
        UUID me = reader(jwt);
        return new NotificationViews.UnreadCount(notifications.count(me, true));
    }

    public NotificationViews.Item markRead(Jwt jwt, UUID id) {
        return transactions.write("NOTIFICATION_BUSY", () -> {
            var actor = actors.lockWriter(jwt);
            actors.requireAll(actor.id(), "SELF_PROFILE_WRITE");
            if (!notifications.markRead(actor.id(), id, now())) throw notFound();
            return NotificationViews.Item.of(notifications.find(actor.id(), id).orElseThrow(NotificationService::notFound));
        });
    }

    public NotificationViews.ReadAll markAllRead(Jwt jwt) {
        return transactions.write("NOTIFICATION_BUSY", () -> {
            var actor = actors.lockWriter(jwt);
            actors.requireAll(actor.id(), "SELF_PROFILE_WRITE");
            return new NotificationViews.ReadAll(notifications.markAllRead(actor.id(), now()), 0);
        });
    }

    private UUID reader(Jwt jwt) {
        UUID me = actors.reader(jwt).getId();
        actors.requireAll(me, "SELF_PROFILE_READ");
        return me;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND", "Không tìm thấy thông báo.");
    }
}
