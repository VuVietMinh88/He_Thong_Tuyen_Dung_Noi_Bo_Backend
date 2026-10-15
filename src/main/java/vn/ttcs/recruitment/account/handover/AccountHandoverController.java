package vn.ttcs.recruitment.account.handover;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts/{id}")
public class AccountHandoverController {
    private final AccountHandoverService service;

    public AccountHandoverController(AccountHandoverService service) {
        this.service = service;
    }

    @GetMapping("/handover-items")
    public ResponseEntity<AccountHandoverView> items(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.items(jwt, id));
    }
}
