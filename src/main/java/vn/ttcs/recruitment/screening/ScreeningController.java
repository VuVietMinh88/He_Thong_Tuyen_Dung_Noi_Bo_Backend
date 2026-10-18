package vn.ttcs.recruitment.screening;

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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/applications/{id}/screening")
public class ScreeningController {
    private final ScreeningService service;
    public ScreeningController(ScreeningService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<ScreeningView> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return response(service.get(jwt, id, page, size));
    }
    @PutMapping("/tags")
    public ResponseEntity<ScreeningView> tags(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody ScreeningTagsRequest request) {
        return response(service.tags(jwt, id, request));
    }
    @PutMapping("/rating")
    public ResponseEntity<ScreeningView> rating(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody ScreeningRatingRequest request) {
        return response(service.rating(jwt, id, request));
    }
    @PostMapping("/notes")
    public ResponseEntity<ScreeningView> note(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody ScreeningNoteRequest request) {
        return response(service.note(jwt, id, request));
    }
    private static ResponseEntity<ScreeningView> response(ScreeningView body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
