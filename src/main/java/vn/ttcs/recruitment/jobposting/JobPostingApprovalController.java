package vn.ttcs.recruitment.jobposting;

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

// Task 308 (story S3-10): HR's review and the publication of job postings. Steps are only added to the history: there
// is no route that edits or deletes one.
@RestController
@RequestMapping("/api/v1/job-postings")
public class JobPostingApprovalController {
    private final JobPostingApprovalService service;

    public JobPostingApprovalController(JobPostingApprovalService service) {
        this.service = service;
    }

    // HR approves the posting or asks its authors for changes.
    @PostMapping("/{id}/approvals")
    public ResponseEntity<JobPostingView> decide(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                 @Valid @RequestBody JobPostingDecisionRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.decide(jwt, id, request));
    }

    // HR publishes the approved posting.
    @PostMapping("/{id}/publish")
    public ResponseEntity<JobPostingView> publish(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                  @Valid @RequestBody JobPostingPublishRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.publish(jwt, id, request));
    }
}
