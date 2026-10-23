package vn.ttcs.recruitment.evaluation;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.util.UUID;

public record EvaluationAnswerRequest(@NotNull UUID criterionId,
                                      @JsonDeserialize(using = EvaluationScoreDeserializer.class) @Min(1) @Max(5) Integer score,
                                      @Size(max = 2000) String comment) {
    @JsonAnySetter
    public void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("Unsupported evaluation answer field");
    }
}
