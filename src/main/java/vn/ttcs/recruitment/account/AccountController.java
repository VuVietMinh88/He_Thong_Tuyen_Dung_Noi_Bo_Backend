package vn.ttcs.recruitment.account;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {
    private final AccountProvisioningService service;
    private final AccountManagementService management;

    public AccountController(AccountProvisioningService service, AccountManagementService management) {
        this.service = service;
        this.management = management;
    }

    @GetMapping
    public ResponseEntity<AccountPage> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(required = false) Role role,
            @RequestParam(required = false) AccountStatus status,
            @RequestParam(required = false) UUID departmentId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(management.list(jwt, q, role, status, departmentId, page, size));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AccountView> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(management.get(jwt, id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<AccountView> update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                             @Valid @RequestBody AccountUpdateRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(management.update(jwt, id, request));
    }

    @PostMapping
    public ResponseEntity<CreatedAccount> create(@AuthenticationPrincipal Jwt jwt,
                                                  @Valid @RequestBody CreateAccountRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(service.create(UUID.fromString(jwt.getSubject()), request));
    }

    public record CreatedAccount(UUID id, String email, String fullName, Set<Role> roles, String status) { }
}
