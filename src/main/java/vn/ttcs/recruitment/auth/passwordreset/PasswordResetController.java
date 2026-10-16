package vn.ttcs.recruitment.auth.passwordreset;

import jakarta.validation.Valid;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.ttcs.recruitment.auth.BrowserDeviceId;
import vn.ttcs.recruitment.common.ApiError;

import java.util.OptionalLong;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
public class PasswordResetController {

    private final PasswordResetDispatcher dispatcher;
    private final PasswordResetService service;
    private final PasswordResetThrottle throttle;

    public PasswordResetController(PasswordResetDispatcher dispatcher, PasswordResetService service,
                                   PasswordResetThrottle throttle) {
        this.dispatcher = dispatcher;
        this.service = service;
        this.throttle = throttle;
    }

    // Order: a malformed body is refused by validation before this runs and is not counted; then the browser id;
    // then the per-browser limit, which counts the request whatever its email is; then the queue. Every answer is
    // the same for known and unknown emails.
    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request,
                                            @RequestHeader(value = BrowserDeviceId.HEADER, required = false)
                                            String deviceId) {
        UUID browser = BrowserDeviceId.parse(deviceId).orElseThrow(PasswordResetDeviceRequiredException::new);
        OptionalLong retryAfter = throttle.admit(browser);
        if (retryAfter.isPresent()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", Long.toString(retryAfter.getAsLong()))
                    .cacheControl(CacheControl.noStore())
                    .body(ApiError.of("PASSWORD_RESET_DEVICE_LOCKED",
                            "Trình duyệt này đã gửi quá nhiều yêu cầu đặt lại mật khẩu. Vui lòng thử lại sau."));
        }
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
