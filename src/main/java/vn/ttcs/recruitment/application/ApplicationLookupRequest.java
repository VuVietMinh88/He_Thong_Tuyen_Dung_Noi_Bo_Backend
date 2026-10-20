package vn.ttcs.recruitment.application;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Task 339: the body of POST /api/v1/public/applications/lookup. Both factors are required: the tracking code of one
 * application (43 Base64URL characters, from the 201 of the intake or the confirmation email) and the email the
 * application was sent from, in any letter case. They travel only in this JSON body, never in a path, query or log.
 * Invisible characters at both edges (spaces, non-breaking spaces, zero-width characters) are trimmed with the intake's
 * rule, so a value without any visible character counts as absent. Two fields, no others.
 */
public record ApplicationLookupRequest(
        @NotBlank(message = "Vui lòng nhập mã tra cứu.")
        @Pattern(regexp = TrackingCode.PATTERN, message = "Mã tra cứu gồm đúng 43 ký tự chữ, số, dấu '-' hoặc '_'.")
        String trackingCode,
        @NotBlank(message = "Vui lòng nhập email đã dùng khi nộp hồ sơ.")
        @Email(message = "Email không hợp lệ.")
        @Size(max = 254, message = "Email tối đa 254 ký tự.")
        String email) {

    public ApplicationLookupRequest {
        trackingCode = ApplicationSubmission.trim(trackingCode);
        email = ApplicationSubmission.trim(email);
    }

    // Nothing else identifies an application to a candidate, so a client cannot send another field.
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unsupported application lookup field");
    }

    @Override public String toString() { return "ApplicationLookupRequest[tracking code and email redacted]"; }
}
