package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class EvaluationReportIntegrationTest extends InterviewEvaluationTestSupport {
    @BeforeEach void reportFixture() {
        jdbc.update("UPDATE job_applications SET stage='INTERVIEW' WHERE id IN (?,?)", first.applicationId(), other.applicationId());
    }
    private String summary() { return "/api/v1/applications/" + first.applicationId() + "/evaluation-summary"; }
    private String comparison() { return "/api/v1/positions/" + position + "/candidate-comparison"; }
    private void error(java.net.http.HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo(code);
    }
    private void submit(UUID session, String token, int a, int b) throws Exception {
        ok(call("POST", ownPath(session), null, token));
        ok(call("POST", ownPath(session) + "/submit", json.writeValueAsString(payload(0, a, b, "HIRE")), token));
    }
    private UUID session(UUID application, long startsOffset, long endsOffset, UUID... users) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(ApplicationTestSupport.NOW);
        jdbc.update("""
                INSERT INTO interview_sessions(id,application_id,round_name,starts_at,ends_at,mode,room,normalized_room,
                    created_by,created_at,updated_at) VALUES (?,?,'Final round',?,?,'ONSITE','Final room','final room',?,?,?)
                """, id, application, Timestamp.from(ApplicationTestSupport.NOW.plusSeconds(startsOffset)),
                Timestamp.from(ApplicationTestSupport.NOW.plusSeconds(endsOffset)), hr, now, now);
        for (UUID user : users) jdbc.update("INSERT INTO interview_participants(interview_id,user_id) VALUES (?,?)", id, user);
        return id;
    }
    private void panelManagesDepartment() {
        jdbc.update("UPDATE departments SET manager_user_id=? WHERE id=(SELECT department_id FROM recruitment_requisitions WHERE id=?)", panel, requisition);
    }

    @Test void comparisonOmitsBlindCandidateWithoutRollingBackOtherVisibleRows() throws Exception {
        panelManagesDepartment();
        submit(interview, peerToken, 5, 5); // Panel has not submitted: this application must be omitted, not a500.
        jdbc.update("UPDATE recruitment_requisitions SET position_id=? WHERE id=?", position, otherRequisition);
        jdbc.update("UPDATE departments SET manager_user_id=? WHERE id=(SELECT department_id FROM recruitment_requisitions WHERE id=?)", panel, otherRequisition);
        UUID second = session(other.applicationId(), -3600, -1800, peer);
        submit(second, peerToken, 3, 4);
        JsonNode result = ok(call("GET", comparison(), null, panelToken));
        assertThat(result.path("totalElements").asLong()).isEqualTo(1);
        assertThat(result.path("items").get(0).path("applicationId").asText()).isEqualTo(other.applicationId().toString());
    }

    @Test void comparisonUsesOnlyFinalRoundBlindRuleWhileSummaryStillProtectsEarlierPeerScores() throws Exception {
        panelManagesDepartment();
        submit(interview, peerToken, 5, 5); // Own old round is missing.
        UUID last = session(first.applicationId(), -1600, -400, panel);
        submit(last, panelToken, 3, 4);
        JsonNode result = ok(call("GET", comparison(), null, panelToken));
        assertThat(result.path("totalElements").asLong()).isEqualTo(1);
        assertThat(result.path("items").get(0).path("aggregate").path("averageTotal").decimalValue()).isEqualByComparingTo("3.70");
        error(call("GET", summary(), null, panelToken), 403, "EVALUATION_BLIND_REVIEW");
    }

    @Test void summaryIncludesOnlySubmittedFormsAndPreservesConclusionsAndAccessAudit() throws Exception {
        submit(interview, panelToken, 2, 5);
        form(peerToken);
        ok(call("PUT", ownPath(), json.writeValueAsString(payload(0, 5, 5, "NO_HIRE")), peerToken));
        JsonNode result = ok(call("GET", summary(), null, hrToken));
        assertThat(result.path("aggregate").path("submittedCount").asInt()).isEqualTo(1);
        assertThat(result.path("aggregate").path("averageTotal").decimalValue()).isEqualByComparingTo("4.10");
        assertThat(result.path("forms").size()).isEqualTo(1);
        assertThat(result.path("forms").get(0).path("conclusion").asText()).isEqualTo("HIRE");
        assertThat(result.path("evidenceHash").asText()).matches("[0-9a-f]{64}");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_access_events WHERE actor_id=? AND application_id=? AND outcome='GRANTED'", Long.class, hr, first.applicationId())).isEqualTo(1);
    }

    @Test void reopenedFormDropsFromSummaryAndComparisonAndChangesEvidenceHash() throws Exception {
        submit(interview, panelToken, 4, 5);
        JsonNode before = ok(call("GET", summary(), null, hrToken));
        UUID form = UUID.fromString(before.path("forms").get(0).path("id").asText());
        ok(call("POST", reopenPath(form), json.writeValueAsString(Map.of("expectedVersion", 1, "reason", "Review again")), recruiterToken));
        JsonNode after = ok(call("GET", summary(), null, hrToken));
        assertThat(after.path("forms").isEmpty()).isTrue();
        assertThat(after.path("aggregate").path("averageTotal").isNull()).isTrue();
        assertThat(after.path("evidenceHash").asText()).isNotEqualTo(before.path("evidenceHash").asText());
        assertThat(ok(call("GET", comparison(), null, hrToken)).path("totalElements").asLong()).isZero();
    }

    @Test void latestPendingRoundNeverUsesEarlierScoresButCancellingItRestoresPreviousFinalRound() throws Exception {
        submit(interview, panelToken, 5, 5);
        UUID future = session(first.applicationId(), 3600, 5400, peer);
        assertThat(ok(call("GET", comparison(), null, hrToken)).path("items").isEmpty()).isTrue();
        jdbc.update("UPDATE interview_sessions SET status='CANCELLED',version=version+1 WHERE id=?", future);
        assertThat(ok(call("GET", comparison(), null, hrToken)).path("totalElements").asLong()).isEqualTo(1);
    }

    @Test void comparisonRanksMeanDescendingPagesAndCountsOnlyPermittedPositionCandidates() throws Exception {
        submit(interview, panelToken, 1, 1);
        assertThat(ok(call("GET", comparison(), null, hrToken)).path("totalElements").asLong()).isEqualTo(1);
        jdbc.update("UPDATE recruitment_requisitions SET position_id=? WHERE id=?", position, otherRequisition);
        UUID second = session(other.applicationId(), -3600, -1800, peer);
        submit(second, peerToken, 5, 5);
        JsonNode page = ok(call("GET", comparison() + "?size=1", null, hrToken));
        assertThat(page.path("totalElements").asLong()).isEqualTo(2);
        assertThat(page.path("items").get(0).path("applicationId").asText()).isEqualTo(other.applicationId().toString());
        JsonNode scoped = ok(call("GET", comparison(), null, recruiterToken));
        assertThat(scoped.path("totalElements").asLong()).isEqualTo(1);
        assertThat(scoped.path("items").get(0).path("applicationId").asText()).isEqualTo(first.applicationId().toString());
        assertThat(ok(call("GET", comparison() + "?page=99", null, hrToken)).path("items").isEmpty()).isTrue();
    }

    @Test void modifiedFrameworkDefinitionsRemainSeparateAndOriginalFormSnapshotsDoNotChange() throws Exception {
        submit(interview, panelToken, 1, 5);
        jdbc.update("UPDATE competency_criteria SET name='Changed technical',weight=40 WHERE id=?", criterionA);
        jdbc.update("UPDATE competency_criteria SET weight=60 WHERE id=?", criterionB);
        submit(interview, peerToken, 5, 1);
        JsonNode result = ok(call("GET", summary(), null, hrToken));
        assertThat(result.path("aggregate").path("criteria").size()).isEqualTo(4);
        assertThat(result.path("aggregate").path("averageTotal").decimalValue()).isEqualByComparingTo("3.20");
        assertThat(result.path("forms").size()).isEqualTo(2);
    }

    @Test void summaryDeniedOutsideScopeLogsRefusalAndRevokedEvaluationPermissionBlocksBothApis() throws Exception {
        submit(interview, panelToken, 4, 4);
        error(call("GET", summary(), null, token(outsider)), 403, "FORBIDDEN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_access_events WHERE actor_id=? AND outcome='DENIED'", Long.class, outsider)).isEqualTo(1);
        List<String> removed = jdbc.queryForList("SELECT permission_code FROM role_permissions WHERE role_code='RECRUITER' AND permission_code LIKE 'EVALUATIONS_READ_%'", String.class);
        jdbc.update("DELETE FROM role_permissions WHERE role_code='RECRUITER' AND permission_code LIKE 'EVALUATIONS_READ_%'");
        try {
            error(call("GET", summary(), null, recruiterToken), 403, "FORBIDDEN");
            error(call("GET", comparison(), null, recruiterToken), 403, "FORBIDDEN");
        } finally { for (String code : removed) jdbc.update("INSERT INTO role_permissions(role_code,permission_code) VALUES ('RECRUITER',?)", code); }
    }

    @Test void nonInterviewCandidatesExcludedAndUnknownIdsAndPaginationValidated() throws Exception {
        submit(interview, panelToken, 4, 4);
        jdbc.update("UPDATE job_applications SET stage='REJECTED' WHERE id=?", first.applicationId());
        assertThat(ok(call("GET", comparison(), null, hrToken)).path("items").isEmpty()).isTrue();
        assertThat(ok(call("GET", summary(), null, hrToken)).path("forms").size()).isEqualTo(1);
        error(call("GET", "/api/v1/applications/" + UUID.randomUUID() + "/evaluation-summary", null, hrToken), 404, "APPLICATION_NOT_FOUND");
        error(call("GET", "/api/v1/positions/" + UUID.randomUUID() + "/candidate-comparison", null, hrToken), 404, "POSITION_NOT_FOUND");
        for (String query : List.of("?size=0", "?size=101", "?page=-1")) error(call("GET", comparison()+query, null, hrToken), 400, "VALIDATION_ERROR");
    }
}
