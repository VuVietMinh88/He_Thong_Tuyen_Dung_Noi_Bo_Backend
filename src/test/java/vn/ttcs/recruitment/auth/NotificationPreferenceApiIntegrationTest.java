package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import vn.ttcs.recruitment.account.Role;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Task 550: each user reads and changes only its own preferences; the mandatory type is refused by the server. */
class NotificationPreferenceApiIntegrationTest extends ScreeningTestSupport {
    private static final String PREFERENCES = "/api/v1/notifications/preferences";

    @BeforeEach void clean() { jdbc.execute("TRUNCATE notification_preferences"); }

    @Test
    void everyoneReadsAllTypesOnByDefaultAndChangesOnlyTheirOwn() throws Exception {
        JsonNode defaults = ok(call("GET", PREFERENCES, null, recruiterToken));
        assertThat(defaults).hasSize(5);
        defaults.forEach(row -> assertThat(row.path("inApp").asBoolean() && row.path("email").asBoolean()).isTrue());
        assertThat(defaults.path(0).path("type").asText()).isEqualTo("APPROVAL_PENDING");
        assertThat(defaults.path(0).path("mandatory").asBoolean()).isTrue();

        JsonNode saved = ok(call("PUT", PREFERENCES, json.writeValueAsString(Map.of("preferences", List.of(
                Map.of("type", "RECRUITER_ASSIGNED", "inApp", true, "email", false),
                Map.of("type", "SLA_OVERDUE", "inApp", false, "email", true)))), recruiterToken));
        assertThat(find(saved, "RECRUITER_ASSIGNED").path("email").asBoolean()).isFalse();
        assertThat(find(saved, "SLA_OVERDUE").path("inApp").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_preferences WHERE user_id=?", Long.class, recruiter))
                .isEqualTo(4);
        // Another user's preferences are untouched; there is no way to name another user.
        assertThat(find(ok(call("GET", PREFERENCES, null, hrToken)), "RECRUITER_ASSIGNED").path("email").asBoolean()).isTrue();
        // An approver holds no NOTIFICATIONS permission but has its own preferences.
        UUID approver = applications.account("Approver", "approver-prefs@example.test", Role.APPROVER);
        assertThat(call("GET", PREFERENCES, null, token(approver)).statusCode()).isEqualTo(200);
    }

    @Test
    void turningOffTheMandatoryTypeIsRefusedAndNothingOfTheRequestIsStored() throws Exception {
        var refused = call("PUT", PREFERENCES, json.writeValueAsString(Map.of("preferences", List.of(
                Map.of("type", "SLA_OVERDUE", "inApp", false, "email", false),
                Map.of("type", "APPROVAL_PENDING", "inApp", true, "email", false)))), recruiterToken);
        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(json.readTree(refused.body()).path("code").asText()).isEqualTo("NOTIFICATION_PREFERENCE_MANDATORY");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_preferences", Long.class)).isZero();
        // Keeping it on is accepted (and stored as on).
        assertThat(call("PUT", PREFERENCES, json.writeValueAsString(Map.of("preferences", List.of(
                Map.of("type", "APPROVAL_PENDING", "inApp", true, "email", true)))), recruiterToken).statusCode()).isEqualTo(200);
    }

    @Test
    void malformedRequestsAreRefused() throws Exception {
        assertThat(code(Map.of("preferences", List.of(Map.of("type", "OTHER", "inApp", true, "email", true))))).isEqualTo("VALIDATION_ERROR");
        assertThat(code(Map.of("preferences", List.of(Map.of("type", "SLA_OVERDUE", "inApp", true, "email", true),
                Map.of("type", "SLA_OVERDUE", "inApp", false, "email", true))))).isEqualTo("VALIDATION_ERROR");
        assertThat(code(Map.of("preferences", List.of(Map.of("type", "SLA_OVERDUE", "inApp", true))))).isEqualTo("VALIDATION_ERROR");
        assertThat(code(Map.of("preferences", List.of()))).isEqualTo("VALIDATION_ERROR");
        var text = call("PUT", PREFERENCES, "{\"preferences\":[{\"type\":\"SLA_OVERDUE\",\"inApp\":\"false\",\"email\":true}]}",
                recruiterToken);
        assertThat(json.readTree(text.body()).path("code").asText()).isEqualTo("INVALID_JSON");
        var extra = call("PUT", PREFERENCES, "{\"userId\":\"" + hr + "\",\"preferences\":[{\"type\":\"SLA_OVERDUE\","
                + "\"inApp\":false,\"email\":true}]}", recruiterToken);
        assertThat(json.readTree(extra.body()).path("code").asText()).isEqualTo("INVALID_JSON");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_preferences", Long.class)).isZero();
    }

    private String code(Map<String, Object> body) throws Exception {
        var response = call("PUT", PREFERENCES, json.writeValueAsString(body), recruiterToken);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        return json.readTree(response.body()).path("code").asText();
    }

    private static JsonNode find(JsonNode rows, String type) {
        for (JsonNode row : rows) if (row.path("type").asText().equals(type)) return row;
        throw new AssertionError(type);
    }
}
