package vn.ttcs.recruitment.competency;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// Jira 215: the URL sits under /positions because the criteria are looked up by position, but the data belongs to
// the competency module (a position only points to its framework), so the controller lives in this package.
@RestController
@RequestMapping("/api/v1/positions/{id}/evaluation-criteria")
public class EvaluationCriteriaController {
    private final EvaluationCriteriaService service;

    public EvaluationCriteriaController(EvaluationCriteriaService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<EvaluationCriteria> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(jwt, id));
    }
}
