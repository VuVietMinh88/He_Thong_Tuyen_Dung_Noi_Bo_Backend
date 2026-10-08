package vn.ttcs.recruitment.approval;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record ApprovalFlowRequest(
        @NotNull(message = "Phải chọn phòng ban.") UUID departmentId,
        @NotBlank(message = "Tên cấu hình không được trống.")
        @Size(max = 120, message = "Tên cấu hình tối đa 120 ký tự.") String name,
        @Min(value = 1, message = "Phiên bản phải lớn hơn 0.") Integer expectedVersion,
        @NotNull(message = "Phải có chuỗi cấp duyệt.")
        @Size(min = 1, max = 20, message = "Chuỗi duyệt phải có từ 1 đến 20 cấp.")
        List<@NotNull @Valid Step> steps) {
    public ApprovalFlowRequest { name = name == null ? null : name.trim(); }

    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) { throw new IllegalArgumentException("Unknown flow field"); }

    public record Step(
            @NotNull @Min(1) @Max(20) Integer position,
            @DecimalMin(value = "1", message = "Ngưỡng lương phải lớn hơn 0.")
            @Digits(integer = 15, fraction = 0, message = "Ngưỡng lương là số nguyên VND tối đa 15 chữ số.") BigDecimal salaryThreshold,
            UUID approverUserId,
            @Size(max = 32) String approverRole) {
        @JsonAnySetter
        public void rejectUnknownField(String field, Object value) { throw new IllegalArgumentException("Unknown step field"); }
    }
}
