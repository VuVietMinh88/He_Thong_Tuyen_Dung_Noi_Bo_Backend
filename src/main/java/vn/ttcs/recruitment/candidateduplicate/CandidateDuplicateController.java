package vn.ttcs.recruitment.candidateduplicate;

import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/applications/{id}/duplicate-candidates")
public class CandidateDuplicateController {
    private final CandidateDuplicateService service;
    public CandidateDuplicateController(CandidateDuplicateService service) { this.service = service; }

    @GetMapping
    public ResponseEntity<DuplicateCandidateView> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(jwt, id, page, size));
    }
}
