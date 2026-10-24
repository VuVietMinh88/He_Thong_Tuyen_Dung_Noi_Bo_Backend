package vn.ttcs.recruitment.reporting;

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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/** Story S8-08: manually entered recruitment costs (task 573). There is no edit and no delete route. */
@RestController
public class RecruitmentCostController {
    private final RecruitmentCostService service;

    public RecruitmentCostController(RecruitmentCostService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/reports/costs")
    public ResponseEntity<RecruitmentCostView> record(@AuthenticationPrincipal Jwt jwt,
                                                      @Valid @RequestBody RecruitmentCostRequests.Create request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(service.record(jwt, request));
    }

    @PostMapping("/api/v1/reports/costs/{id}/void")
    public ResponseEntity<RecruitmentCostView> voidCost(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                        @Valid @RequestBody RecruitmentCostRequests.VoidCost request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.voidCost(jwt, id, request));
    }

    @GetMapping("/api/v1/reports/costs")
    public ResponseEntity<RecruitmentCostPage> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) UUID requisitionId, @RequestParam(required = false) UUID departmentId,
            @RequestParam(required = false) UUID positionId,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate to,
            @RequestParam(required = false) String costType, @RequestParam(required = false) Boolean includeVoided,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(jwt, requisitionId,
                departmentId, positionId, from, to, costType, includeVoided, page, size));
    }
}
