package vn.ttcs.recruitment.position;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

// salaryMin/salaryMax are null for callers who may not see the salary band, and NON_NULL then leaves both keys
// out of the JSON. The band is removed here, on the server, so hiding it never depends on the UI.
// competencyFrameworkId (Jira 214) is always present: null means the position has no framework yet.
public record PositionView(UUID id, String code, String name, String level,
                           @JsonInclude(JsonInclude.Include.NON_NULL) Long salaryMin,
                           @JsonInclude(JsonInclude.Include.NON_NULL) Long salaryMax,
                           boolean active, UUID competencyFrameworkId, Instant createdAt, Instant updatedAt) {

    static PositionView from(Position position, boolean showSalaryBand) {
        return new PositionView(position.getId(), position.getCode(), position.getName(), position.getLevel(),
                showSalaryBand ? position.getSalaryMin() : null, showSalaryBand ? position.getSalaryMax() : null,
                position.isActive(), position.getCompetencyFrameworkId(),
                position.getCreatedAt(), position.getUpdatedAt());
    }
}
