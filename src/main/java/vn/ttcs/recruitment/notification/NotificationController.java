package vn.ttcs.recruitment.notification;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Task 545: the caller's own in-app notifications (docs/api/messaging.md). */
@RestController
public class NotificationController {
    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping("/api/v1/notifications")
    public ResponseEntity<NotificationViews.Page> inbox(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "false") boolean unreadOnly, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(notifications.inbox(jwt, unreadOnly, page, size));
    }

    @GetMapping("/api/v1/notifications/unread-count")
    public ResponseEntity<NotificationViews.UnreadCount> unreadCount(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(notifications.unreadCount(jwt));
    }

    @PatchMapping("/api/v1/notifications/{id}/read")
    public ResponseEntity<NotificationViews.Item> markRead(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(notifications.markRead(jwt, id));
    }

    @PostMapping("/api/v1/notifications/read-all")
    public ResponseEntity<NotificationViews.ReadAll> markAllRead(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(notifications.markAllRead(jwt));
    }
}
