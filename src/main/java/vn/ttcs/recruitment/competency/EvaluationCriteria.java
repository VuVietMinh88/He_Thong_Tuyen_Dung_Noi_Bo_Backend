package vn.ttcs.recruitment.competency;

import java.util.List;
import java.util.UUID;

// Jira 215: what an interview evaluation form of a position is scored with (Sprint 6). The criteria come from the
// competency framework the position uses (Jira 214), ordered by sortOrder. The framework is always ACTIVE, so the
// weights total exactly 100.00. position never carries the salary band: interviewers read this data too.
public record EvaluationCriteria(CompetencyFrameworkPositionView position, Framework framework,
                                 List<CompetencyCriterionView> criteria) {

    // The framework the criteria belong to, so a form can show where its criteria come from.
    public record Framework(UUID id, String code, String name) { }
}
