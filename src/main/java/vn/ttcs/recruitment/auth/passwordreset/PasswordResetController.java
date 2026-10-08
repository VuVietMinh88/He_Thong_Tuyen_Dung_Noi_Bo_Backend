package vn.ttcs.recruitment.auth.passwordreset;

import jakarta.validation.Valid;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.ttcs.recruitment.common.ApiError;

@RestController
@RequestMapping("/api/v1/auth")
public class PasswordResetController {

    private final PasswordResetDispatcher dispatcher;
    private final PasswordResetService service;

    public PasswordResetController(PasswordResetDispatcher dispatcher, PasswordResetService service) {
        this.dispatcher = dispatcher;
        this.service = service;
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        try {
            dispatcher.request(request.email());
        } catch (TaskRejectedException exception) {
            return ResponseEntity.status(503).cacheControl(CacheControl.noStore())
                    .body(ApiError.of("PASSWORD_RESET_BUSY", "Hệ thống đang bận. Vui lòng thử lại sau."));
        }
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(new Message(
                "Nếu email thuộc tài khoản đang hoạt động, bạn sẽ nhận được liên kết đặt lại mật khẩu."));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Message> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        service.resetPassword(request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Message("Đặt lại mật khẩu thành công. Vui lòng đăng nhập bằng mật khẩu mới."));
    }

    public record Message(String message) { }
}
