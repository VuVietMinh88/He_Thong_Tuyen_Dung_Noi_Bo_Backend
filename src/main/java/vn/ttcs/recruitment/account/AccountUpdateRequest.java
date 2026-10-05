package vn.ttcs.recruitment.account;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record AccountUpdateRequest(
        @NotBlank(message = "Họ tên không được để trống.")
        @Size(max = 255, message = "Họ tên tối đa 255 ký tự.") String fullName,
        @Pattern(regexp = ProfileValidation.VIETNAM_PHONE_PATTERN,
                message = "Số điện thoại Việt Nam không đúng định dạng.")
        @Size(max = 20) String phone,
        @Size(max = 120, message = "Chức danh tối đa 120 ký tự.") String displayTitle,
        UUID departmentId) {

    public AccountUpdateRequest {
        fullName = fullName == null ? null : fullName.trim();
        phone = ProfileValidation.normalizePhone(phone);
        displayTitle = ProfileValidation.optionalText(displayTitle);
    }

    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unsupported account field");
    }
}
