package vn.ttcs.recruitment.account.profile;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import vn.ttcs.recruitment.account.ProfileValidation;

public record ProfileUpdateRequest(
        @NotBlank @Size(max = 255) String fullName,
        @Pattern(regexp = ProfileValidation.VIETNAM_PHONE_PATTERN,
                message = "Số điện thoại phải đúng định dạng Việt Nam.") String phone,
        @Size(max = 120) String displayTitle) {

    public ProfileUpdateRequest {
        fullName = fullName == null ? null : fullName.trim();
        phone = ProfileValidation.normalizePhone(phone);
        displayTitle = ProfileValidation.optionalText(displayTitle);
    }

    // Fail instead of silently ignoring forbidden fields such as email, roles or another user's id.
    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Chỉ được cập nhật fullName, phone và displayTitle.");
    }
}
