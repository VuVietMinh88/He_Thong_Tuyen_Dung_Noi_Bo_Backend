package vn.ttcs.recruitment.competency;

import java.util.UUID;

// Jira 214: one position that uses the framework, shown in the framework detail. The salary band is left out on
// purpose: every reader of frameworks sees this list, but only some may see salaries (GET /positions/{id} does that).
public record CompetencyFrameworkPositionView(UUID id, String code, String name, String level, boolean active) { }
