package vn.ttcs.recruitment.candidate;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// Task 361 (story S4-09): the unified candidate profile at the frontend's path (candidateProfileService). The matcher
// in SecurityConfiguration admits CANDIDATES_READ_ALL and CANDIDATES_READ_SCOPED; CandidateProfileService checks the
// permission again, then the scope per application. A malformed {id} fails as 400 VALIDATION_ERROR in
// ApiExceptionHandler. The body holds personal data, so no answer is ever cached.
@RestController
public class CandidateProfileController {
    private final CandidateProfileService service;

    public CandidateProfileController(CandidateProfileService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/candidates/{id}/unified-profile")
    public ResponseEntity<CandidateProfileView> profile(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.profile(jwt, id));
    }
}
