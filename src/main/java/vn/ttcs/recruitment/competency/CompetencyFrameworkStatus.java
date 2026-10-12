package vn.ttcs.recruitment.competency;

public enum CompetencyFrameworkStatus {
    // Still being prepared: criteria weights may not add up to 100% yet.
    DRAFT,
    // Complete: weights add up to exactly 100%, so positions may use the framework.
    ACTIVE
}
