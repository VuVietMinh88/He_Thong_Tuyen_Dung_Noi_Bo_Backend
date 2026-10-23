package vn.ttcs.recruitment.evaluationreport;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;

@RestController
public class EvaluationReportController {
    private final EvaluationReportService service;
    public EvaluationReportController(EvaluationReportService service) { this.service = service; }

    @GetMapping("/api/v1/applications/{id}/evaluation-summary")
    public ResponseEntity<EvaluationReportService.Summary> summary(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.summary(jwt, id));
    }

    @GetMapping("/api/v1/positions/{id}/candidate-comparison")
    public ResponseEntity<EvaluationReportService.Comparison> compare(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.compare(jwt, id, page, size));
    }
}
