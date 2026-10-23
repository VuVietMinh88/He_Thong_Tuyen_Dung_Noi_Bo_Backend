package vn.ttcs.recruitment.offer;

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

@RestController
public class OfferNegotiationController {
    private final OfferNegotiationService service;

    public OfferNegotiationController(OfferNegotiationService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/offers/{id}/negotiations")
    public ResponseEntity<OfferNegotiationView> add(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                   @Valid @RequestBody OfferNegotiationRequests.Note request) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(service.add(jwt, id, request));
    }

    @GetMapping("/api/v1/offers/{id}/negotiations")
    public ResponseEntity<OfferNegotiationView.Page> history(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                           @RequestParam(defaultValue = "0") int page,
                                                           @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.history(jwt, id, page, size));
    }

    @PostMapping("/api/v1/offers/{id}/revisions")
    public ResponseEntity<OfferView> revise(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                          @Valid @RequestBody OfferNegotiationRequests.Revision request) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(service.revise(jwt, id, request));
    }

    @GetMapping("/api/v1/offers/{id}/talent-pool-suggestion")
    public ResponseEntity<OfferNegotiationView.Suggestion> suggestion(@AuthenticationPrincipal Jwt jwt,
                                                                    @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.suggestion(jwt, id));
    }
}
