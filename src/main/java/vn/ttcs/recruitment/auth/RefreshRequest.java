package vn.ttcs.recruitment.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record RefreshRequest(
        @NotBlank(message = "Vui lòng cung cấp refresh token.")
        @Pattern(regexp = "[A-Za-z0-9_-]{43}", message = "Refresh token không đúng định dạng.")
        String refreshToken) {

    @Override
    public String toString() {
        return "RefreshRequest[token redacted]";
    }
}
