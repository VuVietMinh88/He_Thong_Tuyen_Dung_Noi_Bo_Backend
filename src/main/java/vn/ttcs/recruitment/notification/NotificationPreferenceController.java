package vn.ttcs.recruitment.notification;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Task 550: the caller's own notification preferences (docs/api/messaging.md). */
@RestController
public class NotificationPreferenceController {
    private final NotificationPreferenceService preferences;

    public NotificationPreferenceController(NotificationPreferenceService preferences) {
        this.preferences = preferences;
    }

    @GetMapping("/api/v1/notifications/preferences")
    public ResponseEntity<List<NotificationPreferenceService.View>> mine(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(preferences.mine(jwt));
    }

    @PutMapping("/api/v1/notifications/preferences")
    public ResponseEntity<List<NotificationPreferenceService.View>> update(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody NotificationPreferenceService.Update request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(preferences.update(jwt, request));
    }
}
