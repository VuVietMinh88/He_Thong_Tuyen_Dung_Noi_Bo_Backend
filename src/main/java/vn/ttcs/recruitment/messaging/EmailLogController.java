package vn.ttcs.recruitment.messaging;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/** Task 539: the candidate email log and the retry of a failed email (docs/api/messaging.md). */
@RestController
public class EmailLogController {
    private final EmailLogService log;

    public EmailLogController(EmailLogService log) {
        this.log = log;
    }

    @GetMapping("/api/v1/messaging/emails")
    public ResponseEntity<EmailLogViews.Page> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) UUID applicationId, @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(log.list(jwt, new EmailLogService.Filter(applicationId, type, status, from, to, page, size)));
    }

    @GetMapping("/api/v1/messaging/emails/{id}")
    public ResponseEntity<EmailLogViews.Detail> detail(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(log.detail(jwt, id));
    }

    @PostMapping("/api/v1/messaging/emails/{id}/retry")
    public ResponseEntity<EmailLogViews.Message> retry(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ResponseEntity.status(202).cacheControl(CacheControl.noStore()).body(log.retry(jwt, id));
    }
}
