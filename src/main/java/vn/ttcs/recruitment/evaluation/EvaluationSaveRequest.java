package vn.ttcs.recruitment.evaluation;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** PUT replaces draft answers. A submission must explicitly carry every criterion and a conclusion. */
public record EvaluationSaveRequest(@NotNull @Min(0) Long expectedVersion,
                                    @NotNull @Size(max = 50) List<@NotNull @Valid EvaluationAnswerRequest> items,
                                    EvaluationConclusion conclusion) {
    @JsonAnySetter
    public void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("Unsupported evaluation form field");
    }
}
