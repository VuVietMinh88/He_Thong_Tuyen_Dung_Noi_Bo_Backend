package vn.ttcs.recruitment.auth.passwordreset;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Locale;

public record ForgotPasswordRequest(
        @NotBlank(message = "Vui lòng nhập email.")
        @Email(message = "Email không đúng định dạng.")
        @Size(max = 254, message = "Email quá dài.") String email) {

    public ForgotPasswordRequest {
        if (email != null) {
            email = email.trim().toLowerCase(Locale.ROOT);
        }
    }

    @Override
    public String toString() { return "ForgotPasswordRequest[redacted]"; }
}
