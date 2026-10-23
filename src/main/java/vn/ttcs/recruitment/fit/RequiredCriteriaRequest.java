package vn.ttcs.recruitment.fit;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import tools.jackson.databind.annotation.JsonDeserialize;
import vn.ttcs.recruitment.requisition.WholeHeadcountDeserializer;

import java.util.List;

/**
 * Task 412 (story S5-09): the required criteria of a position. A null value (or an empty skill list) means "not
 * required". expectedVersion is the version the caller last read (0 before the first save).
 */
public record RequiredCriteriaRequest(
        @Min(value = 0, message = "Số năm kinh nghiệm từ 0 đến 50.") @Max(value = 50, message = "Số năm kinh nghiệm từ 0 đến 50.")
        @JsonDeserialize(using = WholeHeadcountDeserializer.class) Integer minExperienceYears,
        EducationLevel requiredEducation,
        List<String> requiredSkills,
        @NotNull(message = "Vui lòng gửi phiên bản tiêu chí đã đọc.") @PositiveOrZero(message = "Phiên bản không hợp lệ.")
        Long expectedVersion) {

    @JsonAnySetter
    public void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("Unsupported required criteria field");
    }
}
