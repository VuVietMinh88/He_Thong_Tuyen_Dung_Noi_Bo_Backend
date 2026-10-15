package vn.ttcs.recruitment.cvsuggestion;

import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/applications/{id}/confirmed-contact")
public class ConfirmedContactController {
    private final ConfirmedContactService service;
    public ConfirmedContactController(ConfirmedContactService service) { this.service=service; }
    @GetMapping
    public ResponseEntity<ConfirmedContactView> get(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) {
        return response(service.get(jwt,id));
    }
    @PutMapping
    public ResponseEntity<ConfirmedContactView> confirm(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,
            @Valid @RequestBody ContactConfirmationRequest request) { return response(service.confirm(jwt,id,request)); }
    @GetMapping("/history")
    public ResponseEntity<ConfirmedContactView.HistoryPage> history(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return response(service.history(jwt,id,page,size));
    }
    private static <T> ResponseEntity<T> response(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
}
