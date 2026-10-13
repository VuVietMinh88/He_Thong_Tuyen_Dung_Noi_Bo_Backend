package vn.ttcs.recruitment.jobposting;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// Task 303 (story S3-09): composing job postings from approved requisitions. Task 308 (story S3-10): sending them for
// review. Task 309: the preview, and the public portal of published postings: the /api/v1/public endpoints are for
// candidates, need no login and return only what candidates may see.
@RestController
public class JobPostingController {
    private final JobPostingService service;

    public JobPostingController(JobPostingService service) {
        this.service = service;
    }

    // An unknown status (for example "draft" in lower case) fails as 400 VALIDATION_ERROR in ApiExceptionHandler.
    @GetMapping("/api/v1/job-postings")
    public ResponseEntity<JobPostingPage> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) JobPostingStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(jwt, status, page, size));
    }

    // The approved requisitions the caller may still make a posting from.
    @GetMapping("/api/v1/job-postings/source-requisitions")
    public ResponseEntity<SourceRequisitionPage> sources(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.sources(jwt, page, size));
    }

    @GetMapping("/api/v1/job-postings/{id}")
    public ResponseEntity<JobPostingView> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(jwt, id));
    }

    @PostMapping("/api/v1/job-postings")
    public ResponseEntity<JobPostingView> create(@AuthenticationPrincipal Jwt jwt,
                                                 @Valid @RequestBody JobPostingCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(service.create(jwt, request));
    }

    @PutMapping("/api/v1/job-postings/{id}")
    public ResponseEntity<JobPostingView> update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                 @Valid @RequestBody JobPostingRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.update(jwt, id, request));
    }

    // Task 308: the authors send the saved posting for review. HR's decisions and the publication are in
    // JobPostingApprovalController.
    @PostMapping("/api/v1/job-postings/{id}/submit")
    public ResponseEntity<JobPostingView> submit(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                 @Valid @RequestBody JobPostingSubmitRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.submit(jwt, id, request));
    }

    // Task 309: the posting exactly as candidates will see it, with the status and revision to approve or publish.
    @GetMapping("/api/v1/job-postings/{id}/preview")
    public ResponseEntity<JobPostingPreview> preview(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.preview(jwt, id));
    }

    // Task 309: the published postings candidates may apply to, newest publication first.
    @GetMapping("/api/v1/public/job-postings")
    public ResponseEntity<PublicJobPostingPage> publicPage(@RequestParam(defaultValue = "0") int page,
                                                           @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.publicPage(page, size));
    }

    // Task 309: every posting that is not public answers 404 like an id that does not exist.
    @GetMapping("/api/v1/public/job-postings/{id}")
    public ResponseEntity<PublicJobPostingView> getPublic(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.getPublic(id));
    }
}
