package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OnboardingOfferIntegrationTest extends OfferDeliveryTestSupport {
    @Test
    void acceptedOfferStillSupportsLookupAndIdenticalRetryAfterOnboarding() throws Exception {
        Sent sent = queued();
        ok(respond(sent.token(), "ACCEPT", "Agreed"));
        UUID department = jdbc.queryForObject("SELECT department_id FROM recruitment_requisitions WHERE id=?", UUID.class, requisition);
        var templateResponse = call("POST", "/api/v1/onboarding/templates", json.writeValueAsString(Map.of(
                "departmentId", department, "name", "Offer to employee", "active", true,
                "items", List.of(Map.of("title", "Prepare account", "assigneeId", hr, "dueOffsetDays", 0)))), hrToken);
        assertThat(templateResponse.statusCode()).as(templateResponse.body()).isEqualTo(201);
        JsonNode template = json.readTree(templateResponse.body());
        String path = "/api/v1/applications/" + first.applicationId() + "/onboarding";
        var checklistResponse = call("POST", path, json.writeValueAsString(Map.of(
                "templateId", template.path("id").asText(), "expectedTemplateVersion", 0, "startDate", "2026-10-20")), hrToken);
        assertThat(checklistResponse.statusCode()).as(checklistResponse.body()).isEqualTo(201);
        JsonNode checklist = json.readTree(checklistResponse.body());
        ok(call("PATCH", path + "/items/" + checklist.path("items").get(0).path("id").asText(), json.writeValueAsString(Map.of(
                "expectedVersion", 0, "title", "Prepare account", "assigneeId", hr, "dueDate", "2026-10-20", "status", "DONE")), hrToken));
        assertThat(stage()).isEqualTo("ONBOARDED");
        ok(lookup(sent.token()));
        ok(respond(sent.token(), "ACCEPT", "Agreed"));
        assertThat(respond(sent.token(), "DECLINE", "Changed answer").statusCode()).isEqualTo(409);
        assertThat(stage()).isEqualTo("ONBOARDED");
        assertThat(state(sent.id())).isEqualTo("ACCEPTED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_stage_events WHERE application_id=? AND stage_after='HIRED'",
                Long.class, first.applicationId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_stage_events WHERE application_id=? AND stage_after='ONBOARDED'",
                Long.class, first.applicationId())).isEqualTo(1);
    }
}
