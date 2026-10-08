package vn.ttcs.recruitment.account.profile;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/profile")
public class ProfileController {
    private final ProfileService service;

    public ProfileController(ProfileService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<ProfileResponse> get(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(jwt));
    }

    @PutMapping
    public ResponseEntity<ProfileResponse> update(@AuthenticationPrincipal Jwt jwt,
                                                 @Valid @RequestBody ProfileUpdateRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.update(jwt, request));
    }
}
