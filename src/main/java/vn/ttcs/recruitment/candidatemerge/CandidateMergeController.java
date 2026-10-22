package vn.ttcs.recruitment.candidatemerge;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// Story S5-01: duplicate candidates. Task 367: the candidates that share an email or phone with one candidate. Task
// 368: the side by side preview and the merge of the source into the candidate of the path. Task 370: the undo of a
// merge and the merge history of a candidate. The matchers in SecurityConfiguration admit both CANDIDATES read (or
// write) permissions; the services check the codes again, then the scope per application. A malformed id fails as 400
// VALIDATION_ERROR in ApiExceptionHandler. Every body holds personal data or staff names, so no answer is ever cached.
@RestController
public class CandidateMergeController {
    private final CandidateDuplicateService duplicates;
    private final CandidateMergeService merges;

    public CandidateMergeController(CandidateDuplicateService duplicates, CandidateMergeService merges) {
        this.duplicates = duplicates;
        this.merges = merges;
    }

    @GetMapping("/api/v1/candidates/{id}/duplicates")
    public ResponseEntity<CandidateDuplicatesView> duplicates(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(duplicates.duplicates(jwt, id));
    }

    @GetMapping("/api/v1/candidates/{id}/merge-preview")
    public ResponseEntity<CandidateMergePreview> preview(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                         @RequestParam(required = false) UUID sourceCandidateId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(merges.preview(jwt, id, sourceCandidateId));
    }

    @PostMapping("/api/v1/candidates/{id}/merge")
    public ResponseEntity<CandidateMergeResult> merge(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                      @Valid @RequestBody CandidateMergeRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(merges.merge(jwt, id, request));
    }

    @PostMapping("/api/v1/candidate-merges/{id}/undo")
    public ResponseEntity<CandidateMergeUndoResult> undo(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(merges.undo(jwt, id));
    }

    @GetMapping("/api/v1/candidates/{id}/merges")
    public ResponseEntity<CandidateMergeHistory> history(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(merges.history(jwt, id));
    }
}
