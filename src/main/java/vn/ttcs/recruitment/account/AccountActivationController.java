package vn.ttcs.recruitment.account;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AccountActivationController {
    private final AccountProvisioningService service;

    public AccountActivationController(AccountProvisioningService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/auth/activate-account")
    public ResponseEntity<Message> activate(@Valid @RequestBody ActivationRequest request) {
        service.activate(request.token());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Message("Kích hoạt thành công. Bạn có thể đăng nhập bằng mật khẩu tạm trong email."));
    }

    public record ActivationRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String token) { }
    public record Message(String message) { }
}
