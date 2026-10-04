package vn.ttcs.recruitment.auth;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.nio.charset.StandardCharsets;

public record ChangePasswordRequest(
        @NotBlank(message = "Vui lòng nhập mật khẩu hiện tại.") String currentPassword,
        @NotBlank(message = "Vui lòng nhập mật khẩu mới.")
        @Size(min = 8, max = 72, message = "Mật khẩu mới cần từ 8 đến 72 ký tự.")
        @Pattern(regexp = "(?s)(?=.*\\p{L})(?=.*[0-9]).*",
                message = "Mật khẩu mới cần có chữ và số.") String newPassword) {

    @AssertTrue(message = "Mật khẩu không được vượt quá 72 byte UTF-8.")
    public boolean isWithinBcryptLimit() {
        return (currentPassword == null || currentPassword.getBytes(StandardCharsets.UTF_8).length <= 72)
                && (newPassword == null || newPassword.getBytes(StandardCharsets.UTF_8).length <= 72);
    }

    @Override
    public String toString() { return "ChangePasswordRequest[redacted]"; }
}
