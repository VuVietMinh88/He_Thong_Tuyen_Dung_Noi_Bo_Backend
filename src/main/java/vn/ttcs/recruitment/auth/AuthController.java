package vn.ttcs.recruitment.auth;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.ttcs.recruitment.security.PermissionService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final PermissionService permissions;

    public AuthController(AuthService authService, PermissionService permissions) {
        this.authService = authService;
        this.permissions = permissions;
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(authService.login(request));
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(authService.refresh(request));
    }

    @GetMapping("/me")
    public ResponseEntity<CurrentUserResponse> me(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(CurrentUserResponse.from(authService.requireActiveAccount(jwt)));
    }

    @GetMapping("/permissions")
    public ResponseEntity<CurrentPermissions> permissions(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = authService.requireActiveAccount(jwt).getId();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new CurrentPermissions(permissions.forUser(userId).stream().sorted().toList()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal Jwt jwt) {
        authService.logout(jwt);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    public record CurrentPermissions(List<String> permissions) { }
}
