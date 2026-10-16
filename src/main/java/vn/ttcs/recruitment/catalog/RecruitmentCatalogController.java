package vn.ttcs.recruitment.catalog;

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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

// {type} is the enum name, for example CANDIDATE_SOURCE. The service turns an unknown name into a clear 404.
@RestController
@RequestMapping("/api/v1/recruitment-catalogs/{type}")
public class RecruitmentCatalogController {
    private final RecruitmentCatalogService service;

    public RecruitmentCatalogController(RecruitmentCatalogService service) {
        this.service = service;
    }

    @GetMapping("/items")
    public ResponseEntity<List<RecruitmentCatalogItemView>> list(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String type, @RequestParam(required = false) Boolean active) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(jwt, type, active));
    }

    @GetMapping("/items/{id}")
    public ResponseEntity<RecruitmentCatalogItemView> get(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String type, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(jwt, type, id));
    }

    @PostMapping("/items")
    public ResponseEntity<RecruitmentCatalogItemView> create(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String type, @Valid @RequestBody RecruitmentCatalogItemRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(service.create(jwt, type, request));
    }

    @PutMapping("/items/{id}")
    public ResponseEntity<RecruitmentCatalogItemView> update(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String type, @PathVariable UUID id,
            @Valid @RequestBody RecruitmentCatalogItemRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.update(jwt, type, id, request));
    }

    // 204 with no body when the value is gone; 409 RECRUITMENT_CATALOG_ITEM_IN_USE while other data uses it.
    @DeleteMapping("/items/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String type, @PathVariable UUID id) {
        service.delete(jwt, type, id);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    // Saves a new display order for the whole catalog type and returns every value in that order.
    @PutMapping("/order")
    public ResponseEntity<List<RecruitmentCatalogItemView>> reorder(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String type, @Valid @RequestBody RecruitmentCatalogOrderRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.reorder(jwt, type, request));
    }
}
