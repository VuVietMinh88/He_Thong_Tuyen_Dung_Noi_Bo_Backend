package vn.ttcs.recruitment.account;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {
    private final AccountProvisioningService service;

    public AccountController(AccountProvisioningService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<CreatedAccount> create(@AuthenticationPrincipal Jwt jwt,
                                                  @Valid @RequestBody CreateAccountRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(service.create(UUID.fromString(jwt.getSubject()), request));
    }

    public record CreatedAccount(UUID id, String email, String fullName, Set<Role> roles, String status) { }
}
