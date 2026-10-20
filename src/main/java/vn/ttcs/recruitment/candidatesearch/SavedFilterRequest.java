package vn.ttcs.recruitment.candidatesearch;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import vn.ttcs.recruitment.companyprofile.PlainText;

/**
 * Task 394 (story S5-05): a saved filter's name and criteria. expectedVersion is required by PUT (the version the
 * caller last read) and ignored by POST. The owner is never a field: it is the JWT's subject.
 */
public record SavedFilterRequest(
        @NotBlank(message = "Vui lòng đặt tên bộ lọc.") @Size(max = 80, message = "Tên bộ lọc tối đa 80 ký tự.")
        @PlainText(multiline = false) String name,
        @NotNull(message = "Vui lòng chọn điều kiện lọc.") CandidateSearchCriteria criteria,
        @PositiveOrZero(message = "Phiên bản không hợp lệ.") Long expectedVersion) {

    @JsonAnySetter
    public void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("Unsupported saved filter field");
    }

    @Override
    public String toString() { return "SavedFilterRequest[name redacted, " + criteria + "]"; }
}
