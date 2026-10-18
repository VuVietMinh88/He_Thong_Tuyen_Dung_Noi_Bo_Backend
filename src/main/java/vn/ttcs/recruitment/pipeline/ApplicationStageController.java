package vn.ttcs.recruitment.pipeline;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// Task 380 (story S5-03): moving an application to another stage. POST like the other action routes (CORS allows no
// PATCH); every call adds one row to the stage history, which is never edited or deleted. The matcher in
// SecurityConfiguration admits CANDIDATES_WRITE_ALL and CANDIDATES_WRITE_SCOPED; ApplicationStageChangeService checks
// the write and the read permission again, then the scope of the application. A malformed {id} fails as 400
// VALIDATION_ERROR in ApiExceptionHandler.
@RestController
public class ApplicationStageController {
    private final ApplicationStageChangeService service;

    public ApplicationStageController(ApplicationStageChangeService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/applications/{id}/stage-changes")
    public ResponseEntity<ApplicationStageChangeView> change(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                             @Valid @RequestBody ApplicationStageChangeRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.change(jwt, id, request));
    }
}
