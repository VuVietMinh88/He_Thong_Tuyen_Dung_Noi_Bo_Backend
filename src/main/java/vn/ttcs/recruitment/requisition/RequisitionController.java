package vn.ttcs.recruitment.requisition;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
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
@RequestMapping("/api/v1/requisitions")
public class RequisitionController {
    private final RequisitionService service;

    public RequisitionController(RequisitionService service) {
        this.service = service;
    }

    // An unknown status (for example "draft" in lower case) fails as 400 VALIDATION_ERROR in ApiExceptionHandler.
    @GetMapping
    public ResponseEntity<RequisitionPage> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) RequisitionStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(jwt, status, page, size));
    }

    @GetMapping("/{id}")
    public ResponseEntity<RequisitionView> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(jwt, id));
    }

    @PostMapping
    public ResponseEntity<RequisitionView> create(@AuthenticationPrincipal Jwt jwt,
                                                  @Valid @RequestBody RequisitionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(service.create(jwt, request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<RequisitionView> update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                  @Valid @RequestBody RequisitionRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.update(jwt, id, request));
    }
}
