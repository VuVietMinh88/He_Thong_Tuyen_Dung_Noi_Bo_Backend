package vn.ttcs.recruitment.account.lock;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts/{id}/lock")
public class AccountLockController {
    private final AccountLockService service;

    public AccountLockController(AccountLockService service) {
        this.service = service;
    }

    @PutMapping
    public ResponseEntity<AccountLockResponse> lock(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id, @Valid @RequestBody AccountLockRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.lock(jwt, id, request));
    }

    @DeleteMapping
    public ResponseEntity<AccountLockResponse> unlock(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.unlock(jwt, id));
    }
}
