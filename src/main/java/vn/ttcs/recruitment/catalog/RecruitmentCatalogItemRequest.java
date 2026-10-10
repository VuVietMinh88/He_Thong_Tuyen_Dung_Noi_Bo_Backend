package vn.ttcs.recruitment.catalog;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// Body of POST and PUT. The catalog type comes from the URL and the server sets sortOrder,
// so a body containing "type" or "sortOrder" is rejected like any other unknown field.
public record RecruitmentCatalogItemRequest(
        @NotBlank(message = "Mã giá trị danh mục không được để trống.")
        @Size(max = 50, message = "Mã giá trị danh mục tối đa 50 ký tự.") String code,
        @NotBlank(message = "Tên giá trị danh mục không được để trống.")
        @Size(max = 255, message = "Tên giá trị danh mục tối đa 255 ký tự.") String name,
        @NotNull(message = "Cần xác định giá trị danh mục đang được áp dụng hay không.") Boolean active) {

    public RecruitmentCatalogItemRequest {
        code = code == null ? null : code.trim();
        name = name == null ? null : name.trim();
    }

    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unsupported recruitment catalog field");
    }
}
