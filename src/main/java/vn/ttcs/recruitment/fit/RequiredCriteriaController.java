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

/** Task 412 (story S5-09): the required criteria of a catalog position. */
@RestController
public class RequiredCriteriaController {
    private final RequiredCriteriaService service;

    public RequiredCriteriaController(RequiredCriteriaService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/positions/{id}/required-criteria")
    public ResponseEntity<RequiredCriteriaView> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(jwt, id));
    }

    @PutMapping("/api/v1/positions/{id}/required-criteria")
    public ResponseEntity<RequiredCriteriaView> save(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                     @Valid @RequestBody RequiredCriteriaRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.save(jwt, id, request));
    }
}
