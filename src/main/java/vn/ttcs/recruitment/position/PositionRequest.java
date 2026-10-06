package vn.ttcs.recruitment.position;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

// Text fields are trimmed before validation, so padding never counts toward a length limit.
// Each salary is a whole VND amount from 0 to MAX_SALARY_VND. Comparing the two salaries needs both values,
// so PositionService checks salaryMin <= salaryMax and reports it on the salaryMax field.
public record PositionRequest(
        @NotBlank(message = "Mã chức danh không được để trống.")
        @Size(max = 50, message = "Mã chức danh tối đa 50 ký tự.") String code,
        @NotBlank(message = "Tên chức danh không được để trống.")
        @Size(max = 255, message = "Tên chức danh tối đa 255 ký tự.") String name,
        @NotBlank(message = "Cấp bậc không được để trống.")
        @Size(max = 50, message = "Cấp bậc tối đa 50 ký tự.") String level,
        @NotNull(message = "Lương tối thiểu không được để trống.")
        @PositiveOrZero(message = "Lương tối thiểu không được âm.")
        @Max(value = PositionRequest.MAX_SALARY_VND,
                message = "Lương tối thiểu không được vượt quá 1.000.000.000.000 đồng.")
        @JsonDeserialize(using = WholeVndDeserializer.class) Long salaryMin,
        @NotNull(message = "Lương tối đa không được để trống.")
        @PositiveOrZero(message = "Lương tối đa không được âm.")
        @Max(value = PositionRequest.MAX_SALARY_VND,
                message = "Lương tối đa không được vượt quá 1.000.000.000.000 đồng.")
        @JsonDeserialize(using = WholeVndDeserializer.class) Long salaryMax,
        @NotNull(message = "Cần xác định chức danh đang được áp dụng hay không.") Boolean active) {

    // 1.000 tỷ đồng: far above any real salary, so it only stops typing mistakes such as extra zeros and keeps
    // later offer calculations far from the long limit. V7 has no such CHECK; only the API enforces it.
    // Public because RequisitionRequest (task 244) uses the same ceiling for proposed salaries.
    public static final long MAX_SALARY_VND = 1_000_000_000_000L;

    public PositionRequest {
        code = code == null ? null : code.trim();
        name = name == null ? null : name.trim();
        level = level == null ? null : level.trim();
    }

    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unsupported position field");
    }
}
