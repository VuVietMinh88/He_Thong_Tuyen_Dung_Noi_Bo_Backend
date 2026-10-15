package vn.ttcs.recruitment.requisition;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// Task 290 (story S3-07): HR pauses, resumes, closes as filled or cancels a requisition. POST like the other action
// routes (CORS allows no PATCH); every call adds one row to the history, which is never edited or deleted. Task 291:
// GET /{id}/active-pipeline shows the candidates still in the pipeline before HR closes it. A malformed {id} fails as
// 400 VALIDATION_ERROR in ApiExceptionHandler.
@RestController
@RequestMapping("/api/v1/requisitions")
public class RequisitionStatusController {
    private final RequisitionStatusService service;

    public RequisitionStatusController(RequisitionStatusService service) {
        this.service = service;
    }

    @PostMapping("/{id}/status-changes")
    public ResponseEntity<RequisitionView> change(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                  @Valid @RequestBody RequisitionStatusChangeRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.change(jwt, id, request));
    }

    // Task 291: the candidates still in the pipeline, for the warning before closing.
    @GetMapping("/{id}/active-pipeline")
    public ResponseEntity<ActivePipeline> activePipeline(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.activePipeline(jwt, id));
    }
}
