package vn.ttcs.recruitment.application;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import vn.ttcs.recruitment.companyprofile.PlainText;

import java.util.Locale;
import java.util.UUID;

// Storage input, not an HTTP contract. The service validates it even when called without a controller.
public record ApplicationSubmission(
        @NotBlank @Size(max = 120) @PlainText(multiline = false) String fullName,
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(max = 32) @Pattern(regexp = "\\+?[0-9]{8,15}") String phone,
        UUID sourceId,
        @Size(max = 5000) @PlainText(multiline = true) String coverLetter) {

    public ApplicationSubmission {
        fullName = trim(fullName);
        email = trim(email);
        phone = trim(phone);
        if (phone != null && phone.length() <= 32) phone = phone.replaceAll("[\\s\\p{Zs}().-]", "");
        coverLetter = trim(coverLetter);
    }

    public String normalizedEmail() { return email.toLowerCase(Locale.ROOT); }

    private static String trim(String value) {
        if (value == null) return null;
        int start = 0;
        int end = value.length();
        while (start < end && invisible(value.codePointAt(start))) start += Character.charCount(value.codePointAt(start));
        while (end > start && invisible(value.codePointBefore(end))) end -= Character.charCount(value.codePointBefore(end));
        return start == end ? null : value.substring(start, end);
    }

    private static boolean invisible(int point) {
        return Character.isWhitespace(point) || Character.isSpaceChar(point) || Character.getType(point) == Character.FORMAT;
    }

    @Override public String toString() { return "ApplicationSubmission[personal data redacted]"; }
}
