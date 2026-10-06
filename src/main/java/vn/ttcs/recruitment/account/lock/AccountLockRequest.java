package vn.ttcs.recruitment.account.lock;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AccountLockRequest(
        @NotBlank(message = "Lý do khóa không được để trống.")
        @Size(max = 500, message = "Lý do khóa tối đa 500 ký tự.") String reason) {
    public AccountLockRequest {
        reason = reason == null ? null : reason.strip();
    }

    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unsupported account lock field");
    }
}
