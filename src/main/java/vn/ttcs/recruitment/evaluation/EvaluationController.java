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

    private static <T> ResponseEntity<T> ok(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
}
