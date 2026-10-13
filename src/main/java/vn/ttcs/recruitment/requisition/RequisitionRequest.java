package vn.ttcs.recruitment.requisition;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;
import vn.ttcs.recruitment.position.PositionRequest;
import vn.ttcs.recruitment.position.WholeVndDeserializer;

import java.time.LocalDate;
import java.util.UUID;

// Task 244: what a department head types into a draft requisition. Only the checks a draft needs to be stored are
// here: the four fields V13 always requires, a positive whole headcount, whole non-negative salaries and a size
// limit on each text. Rules that need other data (salary justification, needed-by date, department scope) come in
// later tasks of story S2-10. Every other field may stay empty while the draft is being written.
// Text sections keep the manager's formatting (line breaks, indentation). Only a blank section becomes null,
// because V13 stores a section that has not been written yet as NULL, never as an empty text.
public record RequisitionRequest(
        @NotNull(message = "Chức danh không được để trống.") UUID positionId,
        @NotNull(message = "Phòng ban không được để trống.") UUID departmentId,
        @NotNull(message = "Số lượng cần tuyển không được để trống.")
        @Positive(message = "Số lượng cần tuyển phải lớn hơn 0.")
        @JsonDeserialize(using = WholeHeadcountDeserializer.class) Integer headcount,
        @NotNull(message = "Lý do tuyển không được để trống.") RequisitionReason reason,
        @PositiveOrZero(message = "Lương đề xuất tối thiểu không được âm.")
        @Max(value = PositionRequest.MAX_SALARY_VND,
                message = "Lương đề xuất tối thiểu không được vượt quá 1.000.000.000.000 đồng.")
        @JsonDeserialize(using = WholeVndDeserializer.class) Long proposedSalaryMin,
        @PositiveOrZero(message = "Lương đề xuất tối đa không được âm.")
        @Max(value = PositionRequest.MAX_SALARY_VND,
                message = "Lương đề xuất tối đa không được vượt quá 1.000.000.000.000 đồng.")
        @JsonDeserialize(using = WholeVndDeserializer.class) Long proposedSalaryMax,
        @Size(max = 2_000, message = "Giải trình lương tối đa 2.000 ký tự.")
        @Pattern(regexp = RequisitionRequest.NO_NUL_CHARACTER,
                message = "Giải trình lương chứa ký tự không hợp lệ.")
        String salaryJustification,
        LocalDate neededBy,
        @Size(max = 10_000, message = "Mô tả công việc tối đa 10.000 ký tự.")
        @Pattern(regexp = RequisitionRequest.NO_NUL_CHARACTER,
                message = "Mô tả công việc chứa ký tự không hợp lệ.")
        String jobDescription,
        @Size(max = 10_000, message = "Yêu cầu ứng viên tối đa 10.000 ký tự.")
        @Pattern(regexp = RequisitionRequest.NO_NUL_CHARACTER,
                message = "Yêu cầu ứng viên chứa ký tự không hợp lệ.")
        String candidateRequirements) {

    // PostgreSQL TEXT cannot store the NUL character (code 0, sometimes pasted from other files), so the insert
    // would fail with a 500. Every other character is allowed, including tabs and line breaks.
    // @Pattern skips null, so an empty section is still fine.
    static final String NO_NUL_CHARACTER = "[^\\x00]*";

    public RequisitionRequest {
        salaryJustification = blankToNull(salaryJustification);
        jobDescription = blankToNull(jobDescription);
        candidateRequirements = blankToNull(candidateRequirements);
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }

    // status, createdBy, id... are decided by the server, so a client cannot send them.
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unsupported requisition field");
    }
}
