package vn.ttcs.recruitment.account.role;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.ttcs.recruitment.account.Role;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts/{id}/roles/{role}")
public class AccountRoleController {
    private final AccountRoleService service;

    public AccountRoleController(AccountRoleService service) {
        this.service = service;
    }

    @PutMapping
    public ResponseEntity<AccountRolesResponse> assign(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id, @PathVariable Role role) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.assign(jwt, id, role));
    }

    @DeleteMapping
    public ResponseEntity<AccountRolesResponse> revoke(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id, @PathVariable Role role) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.revoke(jwt, id, role));
    }
}
