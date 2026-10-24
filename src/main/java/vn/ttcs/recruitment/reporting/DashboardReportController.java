package vn.ttcs.recruitment.reporting;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Story S8-05: the HR dashboard. */
@RestController
public class DashboardReportController {
    private final DashboardReportService service;

    public DashboardReportController(DashboardReportService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/reports/dashboard")
    public ResponseEntity<DashboardReportView> dashboard(@AuthenticationPrincipal Jwt jwt,
                                                         @RequestParam(required = false) Integer page,
                                                         @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.dashboard(jwt, page, size));
    }
}
