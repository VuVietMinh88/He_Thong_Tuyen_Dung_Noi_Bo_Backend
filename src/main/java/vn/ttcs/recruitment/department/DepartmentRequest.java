package vn.ttcs.recruitment.department;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record DepartmentRequest(
        @NotBlank(message = "Mã phòng ban không được để trống.")
        @Size(max = 50, message = "Mã phòng ban tối đa 50 ký tự.") String code,
        @NotBlank(message = "Tên phòng ban không được để trống.")
        @Size(max = 255, message = "Tên phòng ban tối đa 255 ký tự.") String name,
        UUID parentId,
        @NotNull(message = "Phòng ban phải có người quản lý.") UUID managerUserId,
        @NotNull(message = "Cần xác định phòng ban đang được áp dụng hay không.") Boolean active) {

    public DepartmentRequest {
        code = code == null ? null : code.trim();
        name = name == null ? null : name.trim();
    }

    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unsupported department field");
    }
}
