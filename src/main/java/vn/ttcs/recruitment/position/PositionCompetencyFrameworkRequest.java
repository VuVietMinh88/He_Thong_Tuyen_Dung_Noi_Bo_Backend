package vn.ttcs.recruitment.position;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

// Body of PUT /api/v1/positions/{id}/competency-framework (Jira 214). frameworkId is required: removing the
// framework is a separate DELETE on the same URL, so a forgotten field never unassigns it by accident.
public record PositionCompetencyFrameworkRequest(
        @NotNull(message = "Cần chọn khung năng lực.") UUID frameworkId) {

    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unsupported position competency framework field");
    }
}
