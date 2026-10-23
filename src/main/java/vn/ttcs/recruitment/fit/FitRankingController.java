package vn.ttcs.recruitment.fit;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.ttcs.recruitment.application.ApplicationStage;

import java.util.List;
import java.util.UUID;

/** Task 414 (story S5-09): the fit ranking suggestion of a requisition's applications. */
@RestController
public class FitRankingController {
    private final FitRankingService service;

    public FitRankingController(FitRankingService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/requisitions/{id}/fit-ranking")
    public ResponseEntity<FitRankingView> rank(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                               @RequestParam(name = "stage", required = false) List<ApplicationStage> stages,
                                               @RequestParam(defaultValue = "false") boolean requiredMetOnly,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.rank(jwt, id, stages, requiredMetOnly, page, size));
    }
}
