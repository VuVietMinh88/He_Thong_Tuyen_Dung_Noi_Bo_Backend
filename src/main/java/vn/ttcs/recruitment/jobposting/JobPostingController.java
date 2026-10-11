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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// Task 303 (story S3-09): composing job postings from approved requisitions.
@RestController
@RequestMapping("/api/v1/job-postings")
public class JobPostingController {
    private final JobPostingService service;

    public JobPostingController(JobPostingService service) {
        this.service = service;
    }

    // An unknown status (for example "draft" in lower case) fails as 400 VALIDATION_ERROR in ApiExceptionHandler.
    @GetMapping
    public ResponseEntity<JobPostingPage> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) JobPostingStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(jwt, status, page, size));
    }

    // The approved requisitions the caller may still make a posting from.
    @GetMapping("/source-requisitions")
    public ResponseEntity<SourceRequisitionPage> sources(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.sources(jwt, page, size));
    }

    @GetMapping("/{id}")
    public ResponseEntity<JobPostingView> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(jwt, id));
    }

    @PostMapping
    public ResponseEntity<JobPostingView> create(@AuthenticationPrincipal Jwt jwt,
                                                 @Valid @RequestBody JobPostingCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(service.create(jwt, request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<JobPostingView> update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                 @Valid @RequestBody JobPostingRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.update(jwt, id, request));
    }
}
