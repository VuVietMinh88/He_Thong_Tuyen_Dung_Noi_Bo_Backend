package vn.ttcs.recruitment.evaluation;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class EvaluationController {
    private final EvaluationService service;
    public EvaluationController(EvaluationService service) { this.service = service; }

    @PostMapping("/api/v1/interviews/{id}/evaluations/me")
    public ResponseEntity<EvaluationView> create(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) { return ok(service.create(jwt, id)); }

    @GetMapping("/api/v1/interviews/{id}/evaluations/me")
    public ResponseEntity<EvaluationView> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) { return ok(service.get(jwt, id)); }

    @PutMapping("/api/v1/interviews/{id}/evaluations/me")
    public ResponseEntity<EvaluationView> save(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody EvaluationSaveRequest request) { return ok(service.save(jwt, id, request, false)); }

    @PostMapping("/api/v1/interviews/{id}/evaluations/me/submit")
    public ResponseEntity<EvaluationView> submit(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody EvaluationSaveRequest request) { return ok(service.save(jwt, id, request, true)); }

    @GetMapping("/api/v1/interviews/{id}/evaluations")
    public ResponseEntity<List<EvaluationView>> peers(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) { return ok(service.peers(jwt, id)); }

    @PostMapping("/api/v1/interviews/{id}/evaluations/{formId}/reopen")
    public ResponseEntity<EvaluationReopenView> reopen(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @PathVariable UUID formId, @Valid @RequestBody EvaluationReopenRequest request) {
        return ok(service.reopen(jwt, id, formId, request));
    }

    @GetMapping("/api/v1/interviews/{id}/evaluations/{formId}/status")
    public ResponseEntity<EvaluationReopenView> status(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @PathVariable UUID formId) { return ok(service.status(jwt, id, formId)); }
    private static <T> ResponseEntity<T> ok(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
}
