package vn.ttcs.recruitment.competency;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

// One framework with all its criteria, used by GET /{id}, POST and PUT. criteria is ordered by sortOrder.
// positions (Jira 214) are the positions that point to this framework, ordered by code; they all share the same
// criteria rows listed here.
public record CompetencyFrameworkView(UUID id, String code, String name, String description,
                                      CompetencyFrameworkStatus status, List<CompetencyCriterionView> criteria,
                                      List<CompetencyFrameworkPositionView> positions,
                                      Instant createdAt, Instant updatedAt) {

    static CompetencyFrameworkView from(CompetencyFramework framework, List<CompetencyCriterion> criteria,
                                        List<CompetencyFrameworkPositionView> positions) {
        return new CompetencyFrameworkView(framework.getId(), framework.getCode(), framework.getName(),
                framework.getDescription(), framework.getStatus(),
                criteria.stream().map(CompetencyCriterionView::from).toList(), positions,
                framework.getCreatedAt(), framework.getUpdatedAt());
    }
}
