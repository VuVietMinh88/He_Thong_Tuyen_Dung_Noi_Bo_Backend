package vn.ttcs.recruitment.interview;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class InterviewController {
    private final InterviewService service;
    public InterviewController(InterviewService service) { this.service = service; }

    @PostMapping("/api/v1/applications/{id}/interviews")
    public ResponseEntity<InterviewView> create(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody InterviewScheduleRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(service.create(jwt, id, request));
    }

    @GetMapping("/api/v1/applications/{id}/interviews")
    public ResponseEntity<InterviewPage> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ok(service.list(jwt, id, page, size));
    }

    @GetMapping("/api/v1/interviews/me")
    public ResponseEntity<InterviewPage> mine(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ok(service.mine(jwt, page, size));
    }

    @GetMapping("/api/v1/interviews/{id}")
    public ResponseEntity<InterviewView> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) { return ok(service.get(jwt, id)); }

    @PostMapping("/api/v1/interviews/conflicts")
    public ResponseEntity<List<InterviewConflict>> conflicts(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody InterviewConflictRequest request) { return ok(service.conflicts(jwt, request)); }

    private static <T> ResponseEntity<T> ok(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
}
