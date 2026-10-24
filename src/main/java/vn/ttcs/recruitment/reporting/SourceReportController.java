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

/** Story S8-09: the quality of candidate sources (tasks 580-582). */
@RestController
public class SourceReportController {
    private final SourceReportService service;

    public SourceReportController(SourceReportService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/reports/sources")
    public ResponseEntity<SourceReportView> report(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate to,
            @RequestParam(required = false) UUID requisitionId, @RequestParam(required = false) UUID departmentId,
            @RequestParam(required = false) UUID positionId, @RequestParam(required = false) Integer minApplications) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.report(jwt, from, to,
                requisitionId, departmentId, positionId, minApplications));
    }
}
