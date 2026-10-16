package vn.ttcs.recruitment.catalog;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

// Body of PUT /recruitment-catalogs/{type}/order: the ids of every value of the catalog type, first value first.
// The service checks that the list is exactly the current values; this record only checks the JSON shape.
public record RecruitmentCatalogOrderRequest(
        @NotNull(message = "Cần gửi danh sách giá trị theo thứ tự mới.")
        List<@NotNull(message = "Mã định danh giá trị không được để trống.") UUID> itemIds) {

    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unsupported recruitment catalog order field");
    }
}
