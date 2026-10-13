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

import java.util.List;
import java.util.UUID;

// Task 284: the recruiters of a requisition. The suffixes /assign (body {recruiterId, note}) and, from task 286,
// /assignment-history (a bare JSON array) match the frontend (AssignRecruiterModal); a malformed {id} fails as
// 400 VALIDATION_ERROR in ApiExceptionHandler.
@RestController
@RequestMapping("/api/v1/requisitions/{id}")
public class RequisitionRecruiterController {
    private final RequisitionRecruiterService service;

    public RequisitionRecruiterController(RequisitionRecruiterService service) {
        this.service = service;
    }

    @GetMapping("/assignment")
    public ResponseEntity<RequisitionAssignmentView> assignment(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.assignment(jwt, id));
    }

    @GetMapping("/assignment-history")
    public ResponseEntity<List<RecruiterChangeView>> history(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.history(jwt, id));
    }

    @PostMapping("/assign")
    public ResponseEntity<RequisitionAssignmentView> assign(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                            @Valid @RequestBody RequisitionAssignRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.assign(jwt, id, request));
    }

    @PostMapping("/unassign")
    public ResponseEntity<RequisitionAssignmentView> unassign(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                              @Valid @RequestBody RequisitionUnassignRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.unassign(jwt, id, request));
    }
}
