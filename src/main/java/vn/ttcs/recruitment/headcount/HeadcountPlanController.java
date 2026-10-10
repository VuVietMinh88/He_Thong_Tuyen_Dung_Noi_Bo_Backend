package vn.ttcs.recruitment.headcount;

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

// Task 273. A malformed id or number in the URL fails as 400 VALIDATION_ERROR in ApiExceptionHandler.
@RestController
@RequestMapping("/api/v1/headcount-plans")
public class HeadcountPlanController {
    private final HeadcountPlanService service;

    public HeadcountPlanController(HeadcountPlanService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<HeadcountPlanView.Page> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) UUID departmentId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.list(jwt, year, departmentId, page, size));
    }

    // departmentId is optional here only so that a missing value is reported as our own 400 VALIDATION_ERROR.
    @GetMapping("/remaining")
    public ResponseEntity<HeadcountRemainingView> remaining(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) UUID departmentId,
            @RequestParam(required = false) Integer year) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.remaining(jwt, departmentId, year));
    }

    @GetMapping("/{id}")
    public ResponseEntity<HeadcountPlanView> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(jwt, id));
    }

    @PostMapping
    public ResponseEntity<HeadcountPlanView> create(@AuthenticationPrincipal Jwt jwt,
                                                    @Valid @RequestBody HeadcountPlanRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(service.create(jwt, request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<HeadcountPlanView> update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                    @Valid @RequestBody HeadcountPlanUpdateRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.update(jwt, id, request));
    }
}
