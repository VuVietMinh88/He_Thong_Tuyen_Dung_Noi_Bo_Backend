package vn.ttcs.recruitment.competency;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.math.BigDecimal;
import java.util.UUID;

// One item of the criteria list in a framework write. id is empty for a new criterion and is the existing id
// for a criterion that must be kept (CompetencyFrameworkService checks that it belongs to the framework).
// There is no sortOrder field: the position in the list is the order.
public record CompetencyCriterionRequest(
        UUID id,
        @NotBlank(message = "Tên tiêu chí không được để trống.")
        @Size(max = 255, message = "Tên tiêu chí tối đa 255 ký tự.") String name,
        @Size(max = 1000, message = "Mô tả tiêu chí tối đa 1000 ký tự.") String description,
        @NotNull(message = "Trọng số không được để trống.")
        @DecimalMin(value = "0", inclusive = false, message = "Trọng số phải lớn hơn 0.")
        @DecimalMax(value = "100", message = "Trọng số không được vượt quá 100.")
        @Digits(integer = 3, fraction = 2, message = "Trọng số là phần trăm có tối đa 2 chữ số thập phân.")
        @JsonDeserialize(using = WeightDeserializer.class) BigDecimal weight) {

    // The name is trimmed like other names. A blank description becomes null, because V8 stores "no description"
    // as NULL and rejects an empty text. Trailing zeros are dropped from the weight (40.000 is 40), so @Digits
    // only fails when a third decimal really matters, such as 33.335.
    public CompetencyCriterionRequest {
        name = name == null ? null : name.trim();
        description = description == null || description.isBlank() ? null : description.strip();
        weight = weight == null ? null : weight.stripTrailingZeros();
    }

    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unsupported competency criterion field");
    }
}
