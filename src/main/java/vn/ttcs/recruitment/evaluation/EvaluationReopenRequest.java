package vn.ttcs.recruitment.evaluation;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record EvaluationReopenRequest(@NotNull @Min(0) Long expectedVersion,
                                      @NotBlank @Size(max = 2000) String reason) {
    @JsonAnySetter
    public void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("Unsupported evaluation reopening field");
    }
}
