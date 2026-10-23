package vn.ttcs.recruitment.hiringdecision;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import vn.ttcs.recruitment.pipeline.ApplicationStageChangeService;

import java.util.UUID;

@RestController
public class HiringDecisionController {
    private final ApplicationStageChangeService pipeline;

    public HiringDecisionController(ApplicationStageChangeService pipeline) { this.pipeline = pipeline; }

    @PostMapping("/api/v1/applications/{id}/hiring-decisions")
    public ResponseEntity<HiringDecisionView> decide(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                    @Valid @RequestBody HiringDecisionRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(pipeline.decide(jwt, id, request));
    }
}
