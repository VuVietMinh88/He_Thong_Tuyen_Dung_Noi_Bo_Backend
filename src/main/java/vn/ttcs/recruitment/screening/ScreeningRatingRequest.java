package vn.ttcs.recruitment.screening;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.annotation.JsonDeserialize;
import vn.ttcs.recruitment.requisition.WholeHeadcountDeserializer;

public record ScreeningRatingRequest(
        @NotNull @Min(1) @Max(5) @JsonDeserialize(using = WholeHeadcountDeserializer.class) Integer rating) {
    @JsonAnySetter
    public void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("Unsupported screening rating field");
    }
}
