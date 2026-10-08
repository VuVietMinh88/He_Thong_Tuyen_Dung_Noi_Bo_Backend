package vn.ttcs.recruitment.account;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Locale;
import java.util.Set;

public record CreateAccountRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(max = 255) String fullName,
        @NotEmpty Set<@NotNull Role> roles) {

    public CreateAccountRequest {
        email = email == null ? null : email.trim().toLowerCase(Locale.ROOT);
        fullName = fullName == null ? null : fullName.trim();
    }
}
