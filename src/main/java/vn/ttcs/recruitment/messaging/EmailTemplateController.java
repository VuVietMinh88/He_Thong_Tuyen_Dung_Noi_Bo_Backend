package vn.ttcs.recruitment.messaging;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Task 532: template management and the personalised preview (docs/api/messaging.md). */
@RestController
public class EmailTemplateController {
    private final EmailTemplateService templates;
    private final EmailPreviewService previews;

    public EmailTemplateController(EmailTemplateService templates, EmailPreviewService previews) {
        this.templates = templates;
        this.previews = previews;
    }

    @GetMapping("/api/v1/messaging/templates")
    public ResponseEntity<List<EmailTemplateView>> list(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(templates.list(jwt));
    }

    @GetMapping("/api/v1/messaging/templates/{type}")
    public ResponseEntity<EmailTemplateView> get(@AuthenticationPrincipal Jwt jwt, @PathVariable String type) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(templates.get(jwt, type));
    }

    @PutMapping("/api/v1/messaging/templates/{type}")
    public ResponseEntity<EmailTemplateView> update(@AuthenticationPrincipal Jwt jwt, @PathVariable String type,
                                                    @Valid @RequestBody EmailTemplateRequests.Update request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(templates.update(jwt, type, request));
    }

    @PostMapping("/api/v1/messaging/templates/{type}/preview")
    public ResponseEntity<EmailPreviewView> preview(@AuthenticationPrincipal Jwt jwt, @PathVariable String type,
                                                    @Valid @RequestBody EmailTemplateRequests.Preview request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(previews.preview(jwt, type, request));
    }
}
