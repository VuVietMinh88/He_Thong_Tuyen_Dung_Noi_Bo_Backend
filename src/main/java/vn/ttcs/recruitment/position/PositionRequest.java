package vn.ttcs.recruitment.position;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

// Salary values are whole, non-negative VND. PositionService checks salaryMin <= salaryMax;
// more detailed salary band rules belong to task 204.
public record PositionRequest(
        @NotBlank(message = "Mã chức danh không được để trống.")
        @Size(max = 50, message = "Mã chức danh tối đa 50 ký tự.") String code,
        @NotBlank(message = "Tên chức danh không được để trống.")
        @Size(max = 255, message = "Tên chức danh tối đa 255 ký tự.") String name,
        @NotBlank(message = "Cấp bậc không được để trống.")
        @Size(max = 50, message = "Cấp bậc tối đa 50 ký tự.") String level,
        @NotNull(message = "Lương tối thiểu không được để trống.")
        @PositiveOrZero(message = "Lương tối thiểu không được âm.")
        @JsonDeserialize(using = WholeVndDeserializer.class) Long salaryMin,
        @NotNull(message = "Lương tối đa không được để trống.")
        @PositiveOrZero(message = "Lương tối đa không được âm.")
        @JsonDeserialize(using = WholeVndDeserializer.class) Long salaryMax,
        @NotNull(message = "Cần xác định chức danh đang được áp dụng hay không.") Boolean active) {

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
