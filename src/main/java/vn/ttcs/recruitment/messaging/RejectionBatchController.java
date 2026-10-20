package vn.ttcs.recruitment.messaging;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Task 526: the personalised preview of rejection letters for a batch of rejected applications. */
@RestController
public class RejectionBatchController {
    private final RejectionBatchService batches;

    public RejectionBatchController(RejectionBatchService batches) {
        this.batches = batches;
    }

    @PostMapping("/api/v1/messaging/rejections/preview")
    public ResponseEntity<RejectionBatchService.Preview> preview(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody RejectionBatchService.PreviewRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(batches.preview(jwt, request));
    }
}
