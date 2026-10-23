package vn.ttcs.recruitment.talentpool;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import vn.ttcs.recruitment.companyprofile.PlainText;

import java.util.List;
import java.util.UUID;

/**
 * Task 398 (story S5-06): a final-stage rejected application to keep in the talent pool, with the reason, the job
 * titles the candidate may fit later and the skills noted. The candidate is the application's profile and the staff
 * member the JWT's subject; neither is a field.
 */
public record TalentPoolAddRequest(
        @NotNull(message = "Vui lòng chọn hồ sơ ứng tuyển.") UUID applicationId,
        @NotBlank(message = "Vui lòng nhập lý do đưa vào kho tiềm năng.")
        @Size(max = 1000, message = "Lý do tối đa 1000 ký tự.") @PlainText(multiline = true) String reason,
        @NotEmpty(message = "Vui lòng nhập ít nhất một chức danh quan tâm.") List<String> titleInterests,
        List<String> skills) {

    @JsonAnySetter
    public void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("Unsupported talent pool field");
    }

    @Override public String toString() { return "TalentPoolAddRequest[" + applicationId + ", text redacted]"; }
}
