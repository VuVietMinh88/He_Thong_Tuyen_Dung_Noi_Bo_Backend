package vn.ttcs.recruitment.cvsuggestion;

import java.net.URI;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/applications/{applicationId}/cv-extractions")
public class CvSuggestionController {
    private final CvSuggestionService service;
    public CvSuggestionController(CvSuggestionService service) { this.service=service; }
    @PostMapping
    public ResponseEntity<CvSuggestionView> request(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID applicationId) {
        var view=service.request(jwt,applicationId);
        return ResponseEntity.status(view.status().equals("SUCCEEDED") ? 200 : 202)
                .cacheControl(CacheControl.noStore())
                .location(URI.create("/api/v1/applications/"+applicationId+"/cv-extractions/"+view.id())).body(view);
    }
    @GetMapping("/{extractionId}")
    public ResponseEntity<CvSuggestionView> get(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID applicationId,
            @PathVariable UUID extractionId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(jwt,applicationId,extractionId));
    }
}
