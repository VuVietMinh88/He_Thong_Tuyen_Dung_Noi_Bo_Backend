package vn.ttcs.recruitment.auth.passwordreset;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.nio.charset.StandardCharsets;

public record ResetPasswordRequest(
        @NotBlank(message = "Vui lòng cung cấp mã đặt lại mật khẩu.")
        @Pattern(regexp = "[A-Za-z0-9_-]{43}", message = "Mã đặt lại mật khẩu không đúng định dạng.") String token,
        @NotBlank(message = "Vui lòng nhập mật khẩu mới.")
        @Size(min = 8, max = 72, message = "Mật khẩu cần từ 8 đến 72 ký tự.")
        @Pattern(regexp = "(?s)(?=.*\\p{L})(?=.*[0-9]).*",
                message = "Mật khẩu cần có chữ và số.") String newPassword) {

    @AssertTrue(message = "Mật khẩu không được vượt quá 72 byte UTF-8.")
    public boolean isPasswordWithinBcryptLimit() {
        return newPassword == null || newPassword.getBytes(StandardCharsets.UTF_8).length <= 72;
    }

    @Override
    public String toString() { return "ResetPasswordRequest[redacted]"; }
}
