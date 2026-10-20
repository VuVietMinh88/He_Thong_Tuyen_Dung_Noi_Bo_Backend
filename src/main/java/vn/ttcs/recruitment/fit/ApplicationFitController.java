package vn.ttcs.recruitment.fit;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Task 413 (story S5-09): one application's fit and the qualifications staff record for it. */
@RestController
public class ApplicationFitController {
    private final ApplicationFitService service;

    public ApplicationFitController(ApplicationFitService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/applications/{id}/fit")
    public ResponseEntity<ApplicationFitView> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(jwt, id));
    }

    @PutMapping("/api/v1/applications/{id}/qualifications")
    public ResponseEntity<ApplicationFitView> record(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                     @Valid @RequestBody QualificationsRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.record(jwt, id, request));
    }
}
