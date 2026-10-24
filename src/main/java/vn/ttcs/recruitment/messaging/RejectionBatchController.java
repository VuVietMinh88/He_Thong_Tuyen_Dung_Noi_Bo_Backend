package vn.ttcs.recruitment.messaging;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Tasks 526/527: the personalised preview and the bulk send of rejection letters for rejected applications. */
@RestController
public class RejectionBatchController {
    private final RejectionBatchService batches;
    private final RejectionSendService sends;

    public RejectionBatchController(RejectionBatchService batches, RejectionSendService sends) {
        this.batches = batches;
        this.sends = sends;
    }

    @PostMapping("/api/v1/messaging/rejections")
    public ResponseEntity<RejectionSendService.SendResult> send(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody RejectionSendService.SendRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(sends.send(jwt, request));
    }

    @PostMapping("/api/v1/messaging/rejections/preview")
    public ResponseEntity<RejectionBatchService.Preview> preview(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody RejectionBatchService.PreviewRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(batches.preview(jwt, request));
    }
}
