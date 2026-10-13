package vn.ttcs.recruitment.requisition;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// Tasks 262-264, 267: the approval of a requisition. Decisions are only added: there is no route that edits or
// deletes one (task 268).
@RestController
@RequestMapping("/api/v1/requisitions")
public class RequisitionApprovalController {
    private final RequisitionApprovalService service;

    public RequisitionApprovalController(RequisitionApprovalService service) {
        this.service = service;
    }

    // Task 262: the current approval level decides.
    @PostMapping("/{id}/approvals")
    public ResponseEntity<RequisitionView> decide(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                  @Valid @RequestBody ApprovalDecisionRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.decide(jwt, id, request));
    }
}
