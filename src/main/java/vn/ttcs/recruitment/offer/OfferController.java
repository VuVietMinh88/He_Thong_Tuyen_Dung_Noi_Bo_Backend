package vn.ttcs.recruitment.offer;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.UUID;

@RestController
public class OfferController {
    private final OfferService service;

    public OfferController(OfferService service) {
        this.service = service;
    }
    @PostMapping("/api/v1/applications/{id}/offers")
    public ResponseEntity<OfferView> create(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody OfferRequests.Draft request) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(service.create(jwt, id, request));
    }
    @GetMapping("/api/v1/applications/{id}/offers")
    public ResponseEntity<OfferView.Page> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ok(service.list(jwt, id, page, size));
    }
    @GetMapping("/api/v1/offers/{id}")
    public ResponseEntity<OfferView> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ok(service.get(jwt, id));
    }
    @PutMapping("/api/v1/offers/{id}")
    public ResponseEntity<OfferView> update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody OfferRequests.Draft request) {
        return ok(service.update(jwt, id, request));
    }
    @GetMapping("/api/v1/offers/{id}/preview")
    public ResponseEntity<OfferView.Preview> preview(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ok(service.preview(jwt, id));
    }
    @PostMapping("/api/v1/offers/{id}/submit")
    public ResponseEntity<OfferView> submit(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody OfferRequests.Submit request) {
        return ok(service.submit(jwt, id, request));
    }
    @PostMapping("/api/v1/offers/{id}/approval")
    public ResponseEntity<OfferView> approve(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody OfferRequests.Decision request) {
        return ok(service.decide(jwt, id, request));
    }
    @PostMapping("/api/v1/offers/{id}/cancel")
    public ResponseEntity<OfferView> cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody OfferRequests.Cancel request) {
        return ok(service.cancel(jwt, id, request));
    }
    @GetMapping("/api/v1/offers/{id}/history")
    public ResponseEntity<OfferView.History> history(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ok(service.history(jwt, id, page, size));
    }
    @GetMapping("/api/v1/offers/approvers")
    public ResponseEntity<List<OfferView.Approver>> approvers(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "HR_MANAGER") String role) {
        return ok(service.approvers(jwt, role));
    }

    private static <T>ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
