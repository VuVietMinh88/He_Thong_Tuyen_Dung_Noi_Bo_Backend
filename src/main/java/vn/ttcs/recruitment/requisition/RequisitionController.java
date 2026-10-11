package vn.ttcs.recruitment.requisition;

import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
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

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/requisitions")
public class RequisitionController {
    private final RequisitionService service;

    public RequisitionController(RequisitionService service) {
        this.service = service;
    }

    // Task 297 (story S3-08): every filter is optional and only narrows what the caller may already see. A malformed value
    // (an unknown or lower-case status, a bad UUID, a date that is not yyyy-MM-dd) fails as 400 VALIDATION_ERROR in
    // ApiExceptionHandler; an empty value is the same as none.
    @GetMapping
    public ResponseEntity<RequisitionPage> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) List<RequisitionStatus> status,
            @RequestParam(required = false) UUID departmentId,
            @RequestParam(required = false) UUID recruiterId,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate endDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(jwt,
                RequisitionListFilter.of(status, departmentId, recruiterId, startDate, endDate), page, size));
    }

    @GetMapping("/{id}")
    public ResponseEntity<RequisitionView> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(jwt, id));
    }

    // Task 275: the HR exceptions to the headcount plan recorded for this requisition.
    @GetMapping("/{id}/headcount-overrides")
    public ResponseEntity<HeadcountOverrideList> headcountOverrides(@AuthenticationPrincipal Jwt jwt,
                                                                    @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.headcountOverrides(jwt, id));
    }

    @PostMapping
    public ResponseEntity<RequisitionView> create(@AuthenticationPrincipal Jwt jwt,
                                                  @Valid @RequestBody RequisitionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(service.create(jwt, request));
    }

    // Task 278: the body is optional; it only carries headcountOverrideReason when the copy goes over the plan.
    @PostMapping("/{id}/copy")
    public ResponseEntity<RequisitionView> copy(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                @Valid @RequestBody(required = false) RequisitionCopyRequest request) {
        String reason = request == null ? null : request.headcountOverrideReason();
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(service.copy(jwt, id, reason));
    }

    // Task 257: sends the saved requisition for approval. The body is optional; it only carries a note for the
    // approvers.
    @PostMapping("/{id}/submit")
    public ResponseEntity<RequisitionView> submit(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                  @Valid @RequestBody(required = false) RequisitionSubmitRequest request) {
        String comment = request == null ? null : request.comment();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.submit(jwt, id, comment));
    }

    @PutMapping("/{id}")
    public ResponseEntity<RequisitionView> update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                  @Valid @RequestBody RequisitionRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.update(jwt, id, request));
    }
}
