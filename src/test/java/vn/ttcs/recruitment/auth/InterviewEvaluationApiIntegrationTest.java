package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InterviewEvaluationApiIntegrationTest extends InterviewEvaluationTestSupport {

    @Test
    void draftAllowsPartialAnswersClearsOmittedAnswersAndCalculatesOnlyCompleteTotals() throws Exception {
        form(panelToken);
        var partial = payload(0, 4, null, null);
        JsonNode firstSave = ok(write("PUT", partial, panelToken));
        assertThat(firstSave.path("version").asLong()).isEqualTo(1);
        assertThat(firstSave.path("totalScore").isNull()).isTrue();
        JsonNode complete = ok(write("PUT", payload(1, 4, 5, null), panelToken));
        assertThat(complete.path("totalScore").decimalValue()).isEqualByComparingTo("4.70");
        assertThat(complete.path("status").asText()).isEqualTo("DRAFT");
        var cleared = payload(2, null, null, null); cleared.put("items", List.of(answer(criterionA, 2, null)));
        JsonNode replaced = ok(write("PUT", cleared, panelToken));
        assertThat(replaced.path("items").get(1).path("score").isNull()).isTrue();
        assertThat(replaced.path("items").get(1).path("comment").isNull()).isTrue();
        assertThat(replaced.path("totalScore").isNull()).isTrue();
        assertThat(count("evaluation_events")).isZero();
    }




    @Test
    void scoreMustBeIntegerOneToFiveAndClientCannotProvideComputedTotal() throws Exception {
        form(panelToken);
        for (int score : new int[]{0, 6}) error(write("PUT", payload(0, score, 3, null), panelToken), 400, "VALIDATION_ERROR");
        for (Object score : List.of(3.5, "4")) {
            var request = payload(0, 3, 4, null);
            var bad = answer(criterionA, 3, null); bad.put("score", score);
            request.put("items", List.of(bad, answer(criterionB, 4, null)));
            assertThat(write("PUT", request, panelToken).statusCode()).isEqualTo(400);
        }
        var forged = payload(0, 3, 4, null); forged.put("totalScore", 5);
        assertThat(write("PUT", forged, panelToken).statusCode()).isEqualTo(400);
        var tooLong = payload(0, 3, 4, null); tooLong.put("items", List.of(answer(criterionA, 3, "x".repeat(2001))));
        error(write("PUT", tooLong, panelToken), 400, "VALIDATION_ERROR");
        assertThat(count("evaluation_events")).isZero();
    }


    @Test
    void onlyInvitedPanelCanAccessOwnEvaluationEvenWhenHrHasAllGrants() throws Exception {
        error(call("POST", ownPath(), null, hrToken), 403, "FORBIDDEN");
        error(call("POST", ownPath(), null, recruiterToken), 403, "FORBIDDEN");
        error(call("GET", ownPath(), null, hrToken), 403, "FORBIDDEN");
        form(panelToken);
        jdbc.update("UPDATE interview_participants SET active=false WHERE interview_id=? AND user_id=?", interview, panel);
        error(call("GET", ownPath(), null, panelToken), 403, "FORBIDDEN");
        error(write("PUT", payload(0, 3, 4, null), panelToken), 403, "FORBIDDEN");
        assertThat(count("evaluation_forms")).isEqualTo(1);
    }

    @Test
    void revokedGrantsAndRevokedSessionApplyImmediately() throws Exception {
        form(panelToken);
        jdbc.update("DELETE FROM role_permissions WHERE role_code='INTERVIEWER' AND permission_code='EVALUATIONS_WRITE_SCOPED'");
        try { error(write("PUT", payload(0, 3, 4, null), panelToken), 403, "FORBIDDEN"); }
        finally { jdbc.update("INSERT INTO role_permissions(role_code,permission_code) VALUES ('INTERVIEWER','EVALUATIONS_WRITE_SCOPED')"); }
        jdbc.update("UPDATE auth_sessions SET revoked_at=? WHERE user_id=?", Timestamp.from(ApplicationTestSupport.NOW), panel);
        assertThat(call("GET", ownPath(), null, panelToken).statusCode()).isEqualTo(401);
        assertThat(write("POST", payload(0, 3, 4, "HIRE"), panelToken).statusCode()).isEqualTo(401);
    }








    @Test
    void missingFrameworkOrInvalidTotalCannotCreatePartialForm() throws Exception {
        jdbc.update("UPDATE positions SET competency_framework_id=NULL WHERE id=?", position);
        error(call("POST", ownPath(), null, panelToken), 409, "POSITION_COMPETENCY_FRAMEWORK_NOT_ASSIGNED");
        jdbc.update("UPDATE positions SET competency_framework_id=? WHERE id=?", framework, position);
        jdbc.update("UPDATE competency_criteria SET weight=20 WHERE framework_id=?", framework);
        error(call("POST", ownPath(), null, panelToken), 409, "EVALUATION_FRAMEWORK_INVALID");
        assertThat(count("evaluation_forms")).isZero();
        assertThat(count("evaluation_form_items")).isZero();
    }





    private HttpResponse<String> write(String method, Map<String, Object> request, String token) throws Exception {
        return call(method, ownPath() + ("POST".equals(method) ? "/submit" : ""), json.writeValueAsString(request), token);
    }
    private HttpResponse<String> reopen(UUID form, long version, String reason, String token) throws Exception {
        return call("POST", reopenPath(form), json.writeValueAsString(Map.of("expectedVersion", version, "reason", reason)), token);
    }
    private String statusPath(UUID interviewId, UUID formId) {
        return "/api/v1/interviews/" + interviewId + "/evaluations/" + formId + "/status";
    }
    private void error(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo(code);
    }
}
