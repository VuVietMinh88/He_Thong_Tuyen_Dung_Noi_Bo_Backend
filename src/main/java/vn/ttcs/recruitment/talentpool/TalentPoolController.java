package vn.ttcs.recruitment.talentpool;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Tasks 398-399 (story S5-06): the talent pool: add, read, search and remove. */
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

    @GetMapping("/api/v1/talent-pool")
    public ResponseEntity<TalentPoolPage> list(@AuthenticationPrincipal Jwt jwt,
                                               @RequestParam(required = false) String title,
                                               @RequestParam(name = "skill", required = false) List<String> skills,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(jwt, title, skills, page, size));
    }

    @DeleteMapping("/api/v1/talent-pool/{id}")
    public ResponseEntity<Void> remove(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.remove(jwt, id);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
