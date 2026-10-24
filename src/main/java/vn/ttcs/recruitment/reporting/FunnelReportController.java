package vn.ttcs.recruitment.reporting;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** Story S8-07: the recruitment funnel (tasks 567-569). */
@RestController
public class FunnelReportController {
    private final FunnelReportService service;

    public FunnelReportController(FunnelReportService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/reports/funnel")
    public ResponseEntity<FunnelReportView> funnel(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate to) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.funnel(jwt, from, to));
    }
}
