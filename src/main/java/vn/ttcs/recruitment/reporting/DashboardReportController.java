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
import java.util.UUID;

/** Story S8-05: the HR dashboard (tasks 555-557). */
@RestController
public class DashboardReportController {
    private final DashboardReportService service;

    public DashboardReportController(DashboardReportService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/reports/dashboard")
    public ResponseEntity<DashboardReportView> dashboard(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate to,
            @RequestParam(required = false) UUID departmentId, @RequestParam(required = false) UUID positionId,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.dashboard(jwt, from, to, departmentId, positionId, page, size));
    }

    @GetMapping("/api/v1/reports/dashboard/charts")
    public ResponseEntity<DashboardChartsView> charts(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate to,
            @RequestParam(required = false) UUID departmentId, @RequestParam(required = false) UUID positionId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.charts(jwt, from, to, departmentId, positionId));
    }
}
