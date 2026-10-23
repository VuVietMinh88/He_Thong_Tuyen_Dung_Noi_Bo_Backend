package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Task 412 (story S5-09): the required criteria of a position, read like the position and saved by HR. */
class RequiredCriteriaIntegrationTest extends ScreeningTestSupport {
    UUID position() {
        return jdbc.queryForObject("SELECT position_id FROM recruitment_requisitions WHERE id = ?", UUID.class, requisition);
    }

    static String criteriaPath(UUID position) {
        return "/api/v1/positions/" + position + "/required-criteria";
    }

    String body(Object years, Object education, List<String> skills, Long version) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("minExperienceYears", years);
        body.put("requiredEducation", education);
        body.put("requiredSkills", skills);
        if (version != null) body.put("expectedVersion", version);
        return json.writeValueAsString(body);
    }

    void assertError(java.net.http.HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo(code);
    }

    @Test
    void hrSavesNormalizedCriteriaAndEveryInternalReaderSeesThem() throws Exception {
        JsonNode empty = ok(call("GET", criteriaPath(position()), null, recruiterToken));
        assertThat(empty.path("configured").asBoolean()).isFalse();
        assertThat(empty.path("version").asLong()).isZero();
        assertThat(empty.path("requiredSkills").size()).isZero();

        JsonNode saved = ok(call("PUT", criteriaPath(position()), body(3, "BACHELOR",
                List.of(" Java ", "spring   boot", "JAVA", "Kế toán"), 0L), hrToken));
        assertThat(saved.path("configured").asBoolean()).isTrue();
        assertThat(saved.path("minExperienceYears").asInt()).isEqualTo(3);
        assertThat(saved.path("requiredEducation").asText()).isEqualTo("BACHELOR");
        assertThat(saved.path("requiredEducationName").asText()).isEqualTo("Đại học");
        assertThat(saved.path("requiredSkills").toString()).isEqualTo("[\"Java\",\"spring boot\",\"Kế toán\"]");
        assertThat(saved.path("version").asLong()).isEqualTo(1);
        assertThat(saved.path("updatedBy").path("id").asText()).isEqualTo(hr.toString());
        assertThat(saved.path("updatedAt").asText()).isEqualTo(ApplicationTestSupport.NOW.toString());
        assertThat(jdbc.queryForList("SELECT skill_key FROM position_required_skills WHERE position_id = ? ORDER BY sort_order",
                String.class, position())).containsExactly("java", "spring boot", "ke toan");

        assertThat(ok(call("GET", criteriaPath(position()), null, token(manager))).path("version").asLong()).isEqualTo(1);
        assertError(call("PUT", criteriaPath(position()), body(5, null, List.of(), 0L), hrToken), 409, "FIT_CRITERIA_CHANGED");
        JsonNode changed = ok(call("PUT", criteriaPath(position()), body(5, null, List.of("Java"), 1L), hrToken));
        assertThat(changed.path("version").asLong()).isEqualTo(2);
        assertThat(changed.path("requiredEducation").isNull()).isTrue();
        JsonNode cleared = ok(call("PUT", criteriaPath(position()), body(null, null, null, 2L), hrToken));
        assertThat(cleared.path("configured").asBoolean()).isFalse();
        assertThat(cleared.path("version").asLong()).isEqualTo(3);
        assertThat(count("position_required_skills")).isZero();
    }

    @Test
    void onlyOrganizationWritersSaveAndUnknownPositionsAreNotFound() throws Exception {
        assertError(call("PUT", criteriaPath(position()), body(3, null, List.of(), 0L), recruiterToken), 403, "FORBIDDEN");
        UUID noRole = applications.account("No role", "none@example.test");
        assertError(call("GET", criteriaPath(position()), null, token(noRole)), 403, "FORBIDDEN");
        assertThat(call("GET", criteriaPath(position()), null, null).statusCode()).isEqualTo(401);
        assertError(call("GET", criteriaPath(UUID.randomUUID()), null, recruiterToken), 404, "POSITION_NOT_FOUND");
        assertError(call("PUT", criteriaPath(UUID.randomUUID()), body(3, null, List.of(), 0L), hrToken), 404, "POSITION_NOT_FOUND");
        jdbc.update("UPDATE auth_sessions SET revoked_at = ? WHERE user_id = ?", Timestamp.from(ApplicationTestSupport.NOW), hr);
        assertThat(call("PUT", criteriaPath(position()), body(3, null, List.of(), 0L), hrToken).statusCode()).isEqualTo(401);
        assertThat(count("position_required_criteria")).isZero();
    }

    @Test
    void invalidCriteriaAreRefusedWithoutChanges() throws Exception {
        List<String> tooMany = IntStream.range(0, 21).mapToObj(index -> "Skill " + index).toList();
        for (String invalid : List.of(body(51, null, List.of(), 0L), body(-1, null, List.of(), 0L),
                body(null, null, tooMany, 0L), body(null, null, List.of("<b>Java</b>"), 0L),
                body(null, null, List.of("x".repeat(61)), 0L), body(null, null, List.of("   "), 0L),
                body(3, null, List.of(), null), body(3, null, List.of(), -1L))) {
            assertError(call("PUT", criteriaPath(position()), invalid, hrToken), 400, "VALIDATION_ERROR");
        }
        for (String invalid : List.of(body(2.5, null, List.of(), 0L), body("3", null, List.of(), 0L),
                body(null, "PHD", List.of(), 0L), "{\"expectedVersion\":0,\"positionId\":\"x\"}", "[]")) {
            assertError(call("PUT", criteriaPath(position()), invalid, hrToken), 400, "INVALID_JSON");
        }
        assertThat(count("position_required_criteria")).isZero();
    }
}
