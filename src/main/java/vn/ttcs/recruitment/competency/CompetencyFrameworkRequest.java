package vn.ttcs.recruitment.competency;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

// Body of POST and PUT. PUT replaces the whole framework, including the whole criteria list.
// An empty list is allowed: a DRAFT framework may be saved before its criteria are written.
// There is no status field: a new framework is always DRAFT and an edit keeps the current status.
public record CompetencyFrameworkRequest(
        @NotBlank(message = "Mã khung năng lực không được để trống.")
        @Size(max = 50, message = "Mã khung năng lực tối đa 50 ký tự.") String code,
        @NotBlank(message = "Tên khung năng lực không được để trống.")
        @Size(max = 255, message = "Tên khung năng lực tối đa 255 ký tự.") String name,
        @Size(max = 1000, message = "Mô tả khung năng lực tối đa 1000 ký tự.") String description,
        @NotNull(message = "Danh sách tiêu chí không được để trống; gửi [] nếu chưa có tiêu chí.")
        @Size(max = CompetencyFrameworkRequest.MAX_CRITERIA,
                message = "Một khung năng lực có tối đa 50 tiêu chí.")
        List<@NotNull(message = "Tiêu chí không được để trống.") @Valid CompetencyCriterionRequest> criteria) {

    // Far more than an interview form needs; it only keeps one request and one evaluation form small.
    static final int MAX_CRITERIA = 50;

    // Same text rules as CompetencyCriterionRequest: trimmed code and name, blank description stored as null.
    public CompetencyFrameworkRequest {
        code = code == null ? null : code.trim();
        name = name == null ? null : name.trim();
        description = description == null || description.isBlank() ? null : description.strip();
    }

    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unsupported competency framework field");
    }
}
