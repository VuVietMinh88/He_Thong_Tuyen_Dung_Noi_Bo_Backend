package vn.ttcs.recruitment.competency;

import java.time.Instant;
import java.util.UUID;

// One row of the framework list: the framework without its criteria, plus how many criteria it has.
// The criteria themselves are read with GET /{id}.
public record CompetencyFrameworkSummary(UUID id, String code, String name, String description,
                                         CompetencyFrameworkStatus status, long criterionCount,
                                         Instant createdAt, Instant updatedAt) {

    static CompetencyFrameworkSummary from(CompetencyFramework framework, long criterionCount) {
        return new CompetencyFrameworkSummary(framework.getId(), framework.getCode(), framework.getName(),
                framework.getDescription(), framework.getStatus(), criterionCount,
                framework.getCreatedAt(), framework.getUpdatedAt());
    }
}
