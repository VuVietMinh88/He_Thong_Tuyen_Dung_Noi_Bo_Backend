package vn.ttcs.recruitment.approval;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/approval-flows")
public class ApprovalFlowController {
    private final ApprovalFlowService service;
    public ApprovalFlowController(ApprovalFlowService service) { this.service = service; }

    @GetMapping
    public ResponseEntity<ApprovalFlowView.Page> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) UUID departmentId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(jwt, departmentId, page, size));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApprovalFlowView> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                               @RequestParam(required = false) Integer version) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(jwt, id, version));
    }

    @PostMapping
    public ResponseEntity<ApprovalFlowView> create(@AuthenticationPrincipal Jwt jwt,
                                                  @Valid @RequestBody ApprovalFlowRequest request) {
        var result = service.create(jwt, request);
        return ResponseEntity.created(URI.create("/api/v1/approval-flows/" + result.id()))
                .cacheControl(CacheControl.noStore()).body(result);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApprovalFlowView> update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                  @Valid @RequestBody ApprovalFlowRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.update(jwt, id, request));
    }

    @PostMapping("/preview")
    public ResponseEntity<ApprovalFlowView> preview(@AuthenticationPrincipal Jwt jwt,
                                                   @Valid @RequestBody ApprovalFlowPreviewRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.preview(jwt, request));
    }
}
