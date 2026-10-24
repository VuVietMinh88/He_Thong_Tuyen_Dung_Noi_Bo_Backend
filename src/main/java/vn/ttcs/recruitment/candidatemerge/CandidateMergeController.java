package vn.ttcs.recruitment.candidatemerge;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// Story S5-01: duplicate candidates. Task 367: the candidates that share an email or phone with one candidate. The
// matcher in SecurityConfiguration admits both CANDIDATES read permissions; the services check the codes again, then
// the scope per application. A malformed id fails as 400 VALIDATION_ERROR in ApiExceptionHandler. Every body holds
// personal data or staff names, so no answer is ever cached.
@RestController
public class CandidateMergeController {
    private final CandidateDuplicateService duplicates;

    public CandidateMergeController(CandidateDuplicateService duplicates) {
        this.duplicates = duplicates;
    }

    @GetMapping("/api/v1/candidates/{id}/duplicates")
    public ResponseEntity<CandidateDuplicatesView> duplicates(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(duplicates.duplicates(jwt, id));
    }
}
