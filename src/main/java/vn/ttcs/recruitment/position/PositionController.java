package vn.ttcs.recruitment.position;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/positions")
public class PositionController {
    private final PositionService service;

    public PositionController(PositionService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<PositionPage> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(jwt, q, active, page, size));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PositionView> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(jwt, id));
    }

    @PostMapping
    public ResponseEntity<PositionView> create(@AuthenticationPrincipal Jwt jwt,
                                               @Valid @RequestBody PositionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(service.create(jwt, request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<PositionView> update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                               @Valid @RequestBody PositionRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.update(jwt, id, request));
    }

    // Jira 214: points the position at a shared competency framework; the criteria are not copied.
    @PutMapping("/{id}/competency-framework")
    public ResponseEntity<PositionView> useCompetencyFramework(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody PositionCompetencyFrameworkRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.useCompetencyFramework(jwt, id, request.frameworkId()));
    }

    // Removes the link only; the framework and its criteria stay for the other positions.
    @DeleteMapping("/{id}/competency-framework")
    public ResponseEntity<PositionView> removeCompetencyFramework(@AuthenticationPrincipal Jwt jwt,
                                                                  @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.removeCompetencyFramework(jwt, id));
    }
}
