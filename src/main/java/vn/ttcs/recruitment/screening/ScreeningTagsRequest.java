package vn.ttcs.recruitment.screening;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

public record ScreeningTagsRequest(@NotNull @Size(max = 30) List<@NotNull UUID> tagIds) {
    @AssertTrue(message = "Mỗi nhãn chỉ được chọn một lần.")
    public boolean isDistinct() {
        return tagIds == null || new HashSet<>(tagIds).size() == tagIds.size();
    }

    @JsonAnySetter
    public void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("Unsupported screening tag field");
    }
}
