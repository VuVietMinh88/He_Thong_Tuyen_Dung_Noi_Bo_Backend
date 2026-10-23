package vn.ttcs.recruitment.talentpool;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Task 398 (story S5-06): the talent pool. */
@RestController
public class TalentPoolController {
    private final TalentPoolService service;

    public TalentPoolController(TalentPoolService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/talent-pool")
    public ResponseEntity<TalentPoolEntryView> add(@AuthenticationPrincipal Jwt jwt,
                                                   @Valid @RequestBody TalentPoolAddRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(service.add(jwt, request));
    }

    @GetMapping("/api/v1/talent-pool/{id}")
    public ResponseEntity<TalentPoolEntryView> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(jwt, id));
    }
}
