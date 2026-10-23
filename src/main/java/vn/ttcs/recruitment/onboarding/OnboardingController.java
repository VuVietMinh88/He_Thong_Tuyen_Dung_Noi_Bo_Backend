package vn.ttcs.recruitment.onboarding;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class OnboardingController {
    private final OnboardingService service;

    public OnboardingController(OnboardingService service) { this.service = service; }

    @GetMapping("/api/v1/onboarding/templates")
    public ResponseEntity<OnboardingTemplateView.Page> templates(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) UUID departmentId, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.templates(jwt, departmentId, page, size));
    }

    @GetMapping("/api/v1/onboarding/templates/{id}")
    public ResponseEntity<OnboardingTemplateView> template(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.template(jwt, id));
    }

    @PostMapping("/api/v1/onboarding/templates")
    public ResponseEntity<OnboardingTemplateView> createTemplate(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody OnboardingRequests.CreateTemplate request) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(service.createTemplate(jwt, request));
    }

    @PutMapping("/api/v1/onboarding/templates/{id}")
    public ResponseEntity<OnboardingTemplateView> updateTemplate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody OnboardingRequests.UpdateTemplate request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.updateTemplate(jwt, id, request));
    }

    @GetMapping("/api/v1/applications/{applicationId}/onboarding")
    public ResponseEntity<OnboardingChecklistView> checklist(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID applicationId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.checklist(jwt, applicationId));
    }

    @PostMapping("/api/v1/applications/{applicationId}/onboarding")
    public ResponseEntity<OnboardingChecklistView> createChecklist(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID applicationId, @Valid @RequestBody OnboardingRequests.CreateChecklist request) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(service.createChecklist(jwt, applicationId, request));
    }

    @PatchMapping("/api/v1/applications/{applicationId}/onboarding/items/{itemId}")
    public ResponseEntity<OnboardingChecklistView> updateItem(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID applicationId, @PathVariable UUID itemId,
            @Valid @RequestBody OnboardingRequests.UpdateItem request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.updateItem(jwt, applicationId, itemId, request));
    }
}
