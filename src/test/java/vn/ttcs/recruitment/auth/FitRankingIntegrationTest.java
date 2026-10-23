package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import vn.ttcs.recruitment.application.ApplicationStage;
import vn.ttcs.recruitment.application.ApplicationSubmission;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Task 414 (story S5-09): the ranking suggestion follows the stored fit and the candidate scope, and moves nothing. */
class FitRankingIntegrationTest extends ScreeningTestSupport {
    ApplicationTestSupport.Stored partial, unknown, strong;

    static String rankingPath(UUID requisitionId) {
        return "/api/v1/requisitions/" + requisitionId + "/fit-ranking";
    }

    void qualifications(UUID application, Object years, String education, List<String> skills) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("experienceYears", years);
        body.put("educationLevel", education);
        body.put("skills", skills);
        body.put("expectedVersion", 0);
        ok(call("PUT", "/api/v1/applications/" + application + "/qualifications", json.writeValueAsString(body),
                recruiterToken));
    }

    void arrange() throws Exception {
        UUID position = jdbc.queryForObject("SELECT position_id FROM recruitment_requisitions WHERE id = ?", UUID.class,
                requisition);
        ok(call("PUT", "/api/v1/positions/" + position + "/required-criteria", json.writeValueAsString(Map.of(
                "minExperienceYears", 3, "requiredEducation", "BACHELOR", "requiredSkills", List.of("Java", "SQL"),
                "expectedVersion", 0)), hrToken));
        UUID posting = jdbc.queryForObject("SELECT id FROM job_postings WHERE requisition_id = ?", UUID.class, requisition);
        partial = applications.stored(posting, new ApplicationSubmission("Ứng viên Một", "mot@example.test", "0901000001", null, null));
        unknown = applications.stored(posting, new ApplicationSubmission("Ứng viên Hai", "hai@example.test", "0901000002", null, null));
        strong = applications.stored(posting, new ApplicationSubmission("Ứng viên Ba", "ba@example.test", "0901000003", null, null));
        qualifications(first.applicationId(), 5, "MASTER", List.of("java", "sql"));
        qualifications(partial.applicationId(), 2, "BACHELOR", List.of("Java"));
        qualifications(strong.applicationId(), 4, "HIGH_SCHOOL", List.of("Java", "SQL"));
    }

    static List<String> order(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.path("items").forEach(item -> ids.add(item.path("applicationId").asText()));
        return ids;
    }

    @Test
    void applicationsAreRankedByTheRequiredFlagThenTheScore() throws Exception {
        arrange();
        JsonNode page = ok(call("GET", rankingPath(requisition), null, recruiterToken));
        assertThat(order(page)).containsExactly(first.applicationId().toString(), strong.applicationId().toString(),
                partial.applicationId().toString(), unknown.applicationId().toString());
        assertThat(page.path("items").get(0).path("rank").asLong()).isEqualTo(1);
        assertThat(page.path("items").get(0).path("fit").path("requiredMet").asBoolean()).isTrue();
        assertThat(page.path("items").get(0).path("fit").path("score").asInt()).isEqualTo(100);
        assertThat(page.path("items").get(1).path("fit").path("score").asInt()).isEqualTo(67);
        assertThat(page.path("items").get(2).path("fit").path("score").asInt()).isEqualTo(50);
        assertThat(page.path("items").get(3).path("fit").path("status").asText()).isEqualTo("UNKNOWN");
        assertThat(page.path("total").asLong()).isEqualTo(4);
        assertThat(page.path("criteria").path("requiredSkills").toString()).isEqualTo("[\"Java\",\"SQL\"]");
        assertThat(page.path("advice").asText()).contains("không tự loại");
        assertThat(page.toString()).doesNotContain("@example.test", "0901000001", "0912345678");

        JsonNode met = ok(call("GET", rankingPath(requisition) + "?requiredMetOnly=true", null, recruiterToken));
        assertThat(order(met)).containsExactly(first.applicationId().toString());
        JsonNode second = ok(call("GET", rankingPath(requisition) + "?size=2&page=1", null, recruiterToken));
        assertThat(second.path("items").get(0).path("rank").asLong()).isEqualTo(3);
        assertThat(second.path("hasNext").asBoolean()).isFalse();
    }

    @Test
    void aCriteriaChangeIsRankedAtOnceBeforeAnyRefresh() throws Exception {
        arrange();
        UUID position = jdbc.queryForObject("SELECT position_id FROM recruitment_requisitions WHERE id = ?", UUID.class,
                requisition);
        // Only Java is required now; the save recomputes nothing, so every stored result is stale.
        ok(call("PUT", "/api/v1/positions/" + position + "/required-criteria", json.writeValueAsString(Map.of(
                "requiredSkills", List.of("Java"), "expectedVersion", 1)), hrToken));
        assertThat(jdbc.queryForList("SELECT DISTINCT criteria_version FROM application_fit_results f"
                + " JOIN job_applications a ON a.id = f.application_id WHERE a.requisition_id = ?", Long.class, requisition))
                .containsExactly(1L);
        JsonNode page = ok(call("GET", rankingPath(requisition), null, recruiterToken));
        assertThat(order(page).subList(0, 3)).containsExactlyInAnyOrder(first.applicationId().toString(),
                strong.applicationId().toString(), partial.applicationId().toString());
        assertThat(order(page).get(3)).isEqualTo(unknown.applicationId().toString());
        for (int index = 0; index < 3; index++) {
            JsonNode fit = page.path("items").get(index).path("fit");
            assertThat(fit.path("status").asText()).isEqualTo("MET");
            assertThat(fit.path("score").asInt()).isEqualTo(100);
            assertThat(fit.path("current").asBoolean()).isFalse();
        }
        assertThat(page.path("criteria").path("version").asLong()).isEqualTo(2);
        assertThat(ok(call("GET", rankingPath(requisition) + "?requiredMetOnly=true", null, recruiterToken))
                .path("total").asLong()).isEqualTo(3);
    }

    @Test
    void onlyActiveStagesAreRankedByDefaultAndNothingMoves() throws Exception {
        arrange();
        applications.stage(partial.applicationId(), ApplicationStage.REJECTED);
        long events = count("application_stage_events");
        JsonNode active = ok(call("GET", rankingPath(requisition), null, recruiterToken));
        assertThat(order(active)).doesNotContain(partial.applicationId().toString()).hasSize(3);
        JsonNode rejected = ok(call("GET", rankingPath(requisition) + "?stage=REJECTED", null, recruiterToken));
        assertThat(order(rejected)).containsExactly(partial.applicationId().toString());
        assertThat(jdbc.queryForList("SELECT stage FROM job_applications WHERE requisition_id = ? ORDER BY stage",
                String.class, requisition)).containsExactly("NEW", "NEW", "NEW", "REJECTED");
        assertThat(count("application_stage_events")).isEqualTo(events);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_access_events WHERE actor_id = ? AND outcome = 'GRANTED'"
                + " AND application_id = ?", Integer.class, recruiter, first.applicationId())).isGreaterThanOrEqualTo(1);
    }

    @Test
    void theRankingFollowsTheCandidateScope() throws Exception {
        assertThat(call("GET", rankingPath(requisition), null, token(outsider)).statusCode()).isEqualTo(403);
        assertThat(ok(call("GET", rankingPath(requisition), null, token(manager))).path("total").asLong()).isEqualTo(1);
        var unknownRequisition = call("GET", rankingPath(UUID.randomUUID()), null, hrToken);
        assertThat(unknownRequisition.statusCode()).isEqualTo(404);
        assertThat(json.readTree(unknownRequisition.body()).path("code").asText()).isEqualTo("REQUISITION_NOT_FOUND");
        UUID noRole = applications.account("No role", "none@example.test");
        assertThat(call("GET", rankingPath(requisition), null, token(noRole)).statusCode()).isEqualTo(403);
        assertThat(call("GET", rankingPath(requisition), null, null).statusCode()).isEqualTo(401);
        for (String invalid : List.of("?size=0", "?size=101", "?page=-1", "?stage=FOO", "?requiredMetOnly=maybe")) {
            assertThat(call("GET", rankingPath(requisition) + invalid, null, recruiterToken).statusCode()).as(invalid)
                    .isEqualTo(400);
        }
        assertThat(count("candidate_access_events")).isEqualTo(1);
    }
}
