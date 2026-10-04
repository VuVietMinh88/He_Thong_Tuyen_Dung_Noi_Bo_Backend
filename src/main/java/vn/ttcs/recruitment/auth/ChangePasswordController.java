package vn.ttcs.recruitment.auth;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class ChangePasswordController {
    private final ChangePasswordService service;

    public ChangePasswordController(ChangePasswordService service) {
        this.service = service;
    }

    @PostMapping("/change-password")
    public ResponseEntity<Message> changePassword(@AuthenticationPrincipal Jwt jwt,
                                                   @Valid @RequestBody ChangePasswordRequest request) {
        service.changePassword(jwt, request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Message("Đổi mật khẩu thành công. Các phiên đăng nhập khác đã được thu hồi."));
    }

    public record Message(String message) { }
}
