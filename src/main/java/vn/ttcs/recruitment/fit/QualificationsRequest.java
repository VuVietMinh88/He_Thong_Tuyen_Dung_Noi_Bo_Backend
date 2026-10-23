package vn.ttcs.recruitment.fit;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.util.List;

/**
 * Task 413 (story S5-09): what a staff member read in an application's CV. A null value means "not recorded": null
 * skills stay UNKNOWN, while an empty list records that the CV shows no skill (every required skill is then missing).
 * expectedVersion is the version the caller last read (0 before the first save).
 */
public record QualificationsRequest(
        @DecimalMin(value = "0", message = "Số năm kinh nghiệm từ 0 đến 60.")
        @DecimalMax(value = "60", message = "Số năm kinh nghiệm từ 0 đến 60.")
        @Digits(integer = 2, fraction = 1, message = "Số năm kinh nghiệm có tối đa một chữ số thập phân.")
        BigDecimal experienceYears,
        EducationLevel educationLevel,
        List<String> skills,
        @NotNull(message = "Vui lòng gửi phiên bản đã đọc.") @PositiveOrZero(message = "Phiên bản không hợp lệ.")
        Long expectedVersion) {

    @JsonAnySetter
    public void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("Unsupported qualifications field");
    }
}
