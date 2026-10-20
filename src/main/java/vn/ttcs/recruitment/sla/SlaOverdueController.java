package vn.ttcs.recruitment.sla;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Task 419: the applications the daily SLA job flagged, within the caller's candidate scope. */
@RestController
public class SlaOverdueController {
    private final SlaOverdueService overdue;

    public SlaOverdueController(SlaOverdueService overdue) {
        this.overdue = overdue;
    }

    @GetMapping("/api/v1/sla/overdue")
    public ResponseEntity<SlaOverdueService.Page> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(overdue.list(jwt, page, size));
    }
}
