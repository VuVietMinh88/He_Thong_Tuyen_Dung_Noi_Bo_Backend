package vn.ttcs.recruitment.sla;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Task 418: the SLA thresholds of the active stages (docs/api/messaging.md). */
@RestController
public class SlaController {
    private final SlaThresholdService thresholds;

    public SlaController(SlaThresholdService thresholds) {
        this.thresholds = thresholds;
    }

    @GetMapping("/api/v1/sla/thresholds")
    public ResponseEntity<List<SlaThresholdService.View>> list(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(thresholds.list(jwt));
    }

    @PutMapping("/api/v1/sla/thresholds/{stage}")
    public ResponseEntity<SlaThresholdService.View> update(@AuthenticationPrincipal Jwt jwt, @PathVariable String stage,
                                                           @Valid @RequestBody SlaThresholdService.Update request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(thresholds.update(jwt, stage, request));
    }
}
