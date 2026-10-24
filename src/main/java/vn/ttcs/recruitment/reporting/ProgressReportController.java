package vn.ttcs.recruitment.reporting;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Story S8-06: the progress of the requisitions a department head owns (tasks 562-563). */
@RestController
public class ProgressReportController {
    private final ProgressReportService service;

    public ProgressReportController(ProgressReportService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/reports/requisitions/{id}/progress")
    public ResponseEntity<RequisitionProgressView> progress(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.progress(jwt, id));
    }
}
