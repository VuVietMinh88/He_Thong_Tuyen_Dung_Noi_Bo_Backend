package vn.ttcs.recruitment.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Locale;

public record LoginRequest(
        @NotBlank(message = "Vui lòng nhập email.")
        @Email(message = "Email không đúng định dạng.")
        @Size(max = 254, message = "Email quá dài.") String email,
        @NotBlank(message = "Vui lòng nhập mật khẩu.")
        @Size(max = 200, message = "Mật khẩu quá dài.") String password) {

    public LoginRequest {
        if (email != null) {
            email = email.trim().toLowerCase(Locale.ROOT);
        }
    }

    @Override
    public String toString() {
        return "LoginRequest[credentials redacted]";
    }
}
