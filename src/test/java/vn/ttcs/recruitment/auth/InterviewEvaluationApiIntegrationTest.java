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
    void explicitCreateIsIdempotentGetNeverCreatesAndSnapshotSurvivesFrameworkDeletion() throws Exception {
        error(call("GET", ownPath(), null, panelToken), 404, "EVALUATION_NOT_FOUND");
        assertThat(count("evaluation_forms")).isZero();
        JsonNode created = form(panelToken);
        assertThat(created.path("status").asText()).isEqualTo("DRAFT");
        assertThat(created.path("items").size()).isEqualTo(2);
        assertThat(created.path("totalScore").isNull()).isTrue();
        assertThat(form(panelToken).path("id").asText()).isEqualTo(created.path("id").asText());
        jdbc.update("UPDATE positions SET competency_framework_id=NULL WHERE id=?", position);
        jdbc.update("DELETE FROM competency_frameworks WHERE id=?", framework);
        JsonNode saved = ok(call("GET", ownPath(), null, panelToken));
        assertThat(saved.path("framework").path("name").asText()).isEqualTo("Interview Framework");
        assertThat(saved.path("items").get(0).path("name").asText()).isEqualTo("Technical");
        assertThat(saved.path("items").get(0).path("weight").decimalValue()).isEqualByComparingTo("30");
        assertThat(form(panelToken).path("id").asText()).isEqualTo(created.path("id").asText());
        assertThat(count("evaluation_forms")).isEqualTo(1);
    }

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
    void submitUsesServerWeightsAndStoresImmutableSnapshotAndPermanentScheduleMarker() throws Exception {
        form(panelToken);
        JsonNode submitted = ok(write("POST", payload(0, 2, 5, "CONSIDER"), panelToken));
        assertThat(submitted.path("totalScore").decimalValue()).isEqualByComparingTo("4.10");
        assertThat(submitted.path("status").asText()).isEqualTo("SUBMITTED");
        assertThat(submitted.path("firstSubmittedAt").asText()).isEqualTo(ApplicationTestSupport.NOW.toString());
        assertThat(jdbc.queryForObject("SELECT ever_submitted_at FROM interview_sessions WHERE id=?", Timestamp.class, interview).toInstant())
                .isEqualTo(ApplicationTestSupport.NOW);
        String snapshot = jdbc.queryForObject("SELECT submission_snapshot::text FROM evaluation_events WHERE interview_id=?", String.class, interview);
        assertThat(json.readTree(snapshot).path("totalScore").decimalValue()).isEqualByComparingTo("4.10");
        assertThat(json.readTree(snapshot).path("items").get(0).path("comment").asText()).isEqualTo("Technical note");
        assertThatThrownBy(() -> jdbc.update("UPDATE evaluation_events SET reason='tamper' WHERE interview_id=?", interview))
                .rootCause().hasMessageContaining("evaluation_events rows are never changed");
    }


    @Test
    void submitRejectsMissingNullDuplicateAndForeignCriteriaOrMissingConclusionWithoutPartialChanges() throws Exception {
        form(panelToken);
        var missing = payload(0, 3, 4, "HIRE"); missing.put("items", List.of(answer(criterionA, 3, null)));
        var duplicate = payload(0, 3, 4, "HIRE"); duplicate.put("items", List.of(answer(criterionA, 3, null), answer(criterionA, 4, null)));
        var unknown = payload(0, 3, 4, "HIRE"); unknown.put("items", List.of(answer(criterionA, 3, null), answer(UUID.randomUUID(), 4, null)));
        for (var invalid : List.of(missing, duplicate, unknown, payload(0, 3, null, "HIRE"), payload(0, 3, 4, null))) {
            error(write("POST", invalid, panelToken), 400, "VALIDATION_ERROR");
        }
        assertThat(ok(call("GET", ownPath(), null, panelToken)).path("version").asLong()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM evaluation_form_items WHERE score IS NOT NULL", Long.class)).isZero();
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
    void submittedFormsCannotBeEditedOrResubmittedAndVersionGuardsDrafts() throws Exception {
        form(panelToken);
        ok(write("PUT", payload(0, 3, null, null), panelToken));
        error(write("PUT", payload(0, 4, 5, "HIRE"), panelToken), 409, "EVALUATION_VERSION_CHANGED");
        ok(write("POST", payload(1, 3, 4, "CONSIDER"), panelToken));
        error(write("PUT", payload(2, 5, 5, "HIRE"), panelToken), 409, "EVALUATION_LOCKED");
        error(write("POST", payload(2, 5, 5, "HIRE"), panelToken), 409, "EVALUATION_LOCKED");
        assertThat(count("evaluation_events")).isEqualTo(1);
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
    void notFinishedInterviewAllowsDraftButRejectsSubmissionUntilEndBoundary() throws Exception {
        jdbc.update("UPDATE interview_sessions SET starts_at=?,ends_at=? WHERE id=?",
                Timestamp.from(ApplicationTestSupport.NOW.minusSeconds(60)), Timestamp.from(ApplicationTestSupport.NOW.plusSeconds(60)), interview);
        form(panelToken);
        ok(write("PUT", payload(0, 3, 4, "HIRE"), panelToken));
        error(write("POST", payload(1, 3, 4, "HIRE"), panelToken), 409, "EVALUATION_NOT_FINISHED");
        clock.set(ApplicationTestSupport.NOW.plusSeconds(60));
        ok(write("POST", payload(1, 3, 4, "HIRE"), panelToken));
    }

    @Test
    void cancelledInterviewRejectsCreateSaveAndRead() throws Exception {
        form(panelToken);
        jdbc.update("UPDATE interview_sessions SET status='CANCELLED' WHERE id=?", interview);
        error(call("POST", ownPath(), null, peerToken), 409, "INTERVIEW_CANCELLED");
        error(call("GET", ownPath(), null, panelToken), 409, "INTERVIEW_CANCELLED");
        error(write("POST", payload(0, 3, 4, "HIRE"), panelToken), 409, "INTERVIEW_CANCELLED");
    }



    @Test
    void onlyAssignedRecruiterMayReopenWithReasonAndCurrentVersion() throws Exception {
        UUID id = UUID.fromString(form(panelToken).path("id").asText());
        ok(write("POST", payload(0, 3, 4, "CONSIDER"), panelToken));
        error(reopen(id, 1, "Lý do", hrToken), 403, "FORBIDDEN");
        error(reopen(id, 1, "Lý do", token(outsider)), 403, "FORBIDDEN");
        error(reopen(id, 1, " ", recruiterToken), 400, "VALIDATION_ERROR");
        error(reopen(id, 0, "Lý do", recruiterToken), 409, "EVALUATION_VERSION_CHANGED");
        ok(reopen(id, 1, "Lý do", recruiterToken));
        error(reopen(id, 2, "Lý do", recruiterToken), 409, "EVALUATION_NOT_SUBMITTED");
    }

    @Test
    void reopeningAtExactTwentyFourHourBoundaryIsRefusedAndResubmissionDoesNotExtendIt() throws Exception {
        UUID id = UUID.fromString(form(panelToken).path("id").asText());
        ok(write("POST", payload(0, 3, 4, "CONSIDER"), panelToken));
        clock.set(ApplicationTestSupport.NOW.plus(23, ChronoUnit.HOURS));
        ok(reopen(id, 1, "Sửa nhận xét", token(recruiter)));
        JsonNode secondSubmission = ok(write("POST", payload(2, 4, 5, "HIRE"), token(panel)));
        assertThat(secondSubmission.path("firstSubmittedAt").asText()).isEqualTo(ApplicationTestSupport.NOW.toString());
        clock.set(ApplicationTestSupport.NOW.plus(24, ChronoUnit.HOURS));
        error(reopen(id, 3, "Không được gia hạn", token(recruiter)), 409, "EVALUATION_REOPEN_EXPIRED");
        assertThat(count("evaluation_events")).isEqualTo(3);
        List<String> snapshots = jdbc.queryForList("SELECT submission_snapshot::text FROM evaluation_events WHERE event_type='EVALUATION_SUBMITTED' ORDER BY occurred_at", String.class);
        assertThat(json.readTree(snapshots.get(0)).path("totalScore").decimalValue()).isEqualByComparingTo("3.70");
        assertThat(json.readTree(snapshots.get(1)).path("totalScore").decimalValue()).isEqualByComparingTo("4.70");
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

    @Test
    void permissionScopeCannotBeWidenedByCandidateAllOnRecruiterReopening() throws Exception {
        UUID id = UUID.fromString(form(panelToken).path("id").asText());
        ok(write("POST", payload(0, 3, 4, "HIRE"), panelToken));
        // A recruiter with global candidate access still obeys a narrowed interview module permission.
        jdbc.update("UPDATE role_permissions SET permission_code='CANDIDATES_READ_ALL' WHERE role_code='RECRUITER' AND permission_code='CANDIDATES_READ_SCOPED'");
        jdbc.update("UPDATE role_permissions SET permission_code='INTERVIEWS_WRITE_SCOPED' WHERE role_code='RECRUITER' AND permission_code='INTERVIEWS_WRITE_ALL'");
        try {
            error(call("GET", statusPath(interview, id), null, token(outsider)), 403, "FORBIDDEN");
            error(reopen(id, 1, "Không phụ trách", token(outsider)), 403, "FORBIDDEN");
            assertThat(jdbc.queryForList("SELECT access_scope FROM candidate_access_events WHERE actor_id=? AND outcome='DENIED'", String.class, outsider))
                    .hasSize(2).containsOnly("SCOPED");
            applications.assign(requisition, outsider, "SUPPORTING", hr);
            JsonNode status = ok(call("GET", statusPath(interview, id), null, token(outsider)));
            ok(reopen(id, status.path("version").asLong(), "Đã được phân công", token(outsider)));
        } finally {
            jdbc.update("UPDATE role_permissions SET permission_code='CANDIDATES_READ_SCOPED' WHERE role_code='RECRUITER' AND permission_code='CANDIDATES_READ_ALL'");
            jdbc.update("UPDATE role_permissions SET permission_code='INTERVIEWS_WRITE_ALL' WHERE role_code='RECRUITER' AND permission_code='INTERVIEWS_WRITE_SCOPED'");
        }
    }

    @Test
    void recruiterReadsMetadataVersionThenReopensWithoutSeeingAnyScorecardAnswers() throws Exception {
        UUID id = UUID.fromString(form(panelToken).path("id").asText());
        ok(write("POST", payload(0, 4, 5, "HIRE"), panelToken));
        long eventsBeforeRead = count("evaluation_events");
        JsonNode status = ok(call("GET", statusPath(interview, id), null, recruiterToken));
        assertThat(status.size()).isEqualTo(6);
        for (String field : List.of("id", "interviewId", "status", "version", "firstSubmittedAt", "updatedAt")) {
            assertThat(status.has(field)).as(field).isTrue();
        }
        assertThat(status.path("id").asText()).isEqualTo(id.toString());
        assertThat(status.path("interviewId").asText()).isEqualTo(interview.toString());
        assertThat(status.path("status").asText()).isEqualTo("SUBMITTED");
        assertThat(status.toString()).doesNotContain("score", "comment", "items", "conclusion", "Technical note", "Team note");
        assertThat(count("evaluation_events")).isEqualTo(eventsBeforeRead);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_access_events WHERE actor_id=? AND application_id=? AND outcome='GRANTED'",
                Long.class, recruiter, first.applicationId())).isEqualTo(1);
        JsonNode reopened = ok(reopen(id, status.path("version").asLong(), "Sửa nhận xét sau kiểm tra", recruiterToken));
        assertThat(reopened.path("status").asText()).isEqualTo("DRAFT");
        assertThat(reopened.path("version").asLong()).isEqualTo(status.path("version").asLong() + 1);
        assertThat(ok(call("GET", statusPath(interview, id), null, recruiterToken)).path("version").asLong())
                .isEqualTo(reopened.path("version").asLong());
    }

    @Test
    void metadataStatusRejectsNonRecruiterOutsideScopeAndMismatchedInterviewAndForm() throws Exception {
        UUID id = UUID.fromString(form(panelToken).path("id").asText());
        error(call("GET", statusPath(interview, id), null, panelToken), 403, "FORBIDDEN");
        error(call("GET", statusPath(interview, id), null, hrToken), 403, "FORBIDDEN");
        error(call("GET", statusPath(interview, id), null, token(outsider)), 403, "FORBIDDEN");
        error(call("GET", statusPath(interview, UUID.randomUUID()), null, recruiterToken), 404, "EVALUATION_NOT_FOUND");
        error(call("GET", statusPath(UUID.randomUUID(), id), null, recruiterToken), 404, "INTERVIEW_NOT_FOUND");
        UUID differentInterview = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO interview_sessions(id,application_id,round_name,starts_at,ends_at,mode,room,normalized_room,
                    created_by,created_at,updated_at)
                SELECT ?,application_id,'Other round',starts_at,ends_at,mode,room,normalized_room,
                    created_by,created_at,updated_at FROM interview_sessions WHERE id=?
                """, differentInterview, interview);
        error(call("GET", statusPath(differentInterview, id), null, recruiterToken), 404, "EVALUATION_NOT_FOUND");
        // The status route needs the same read+write management grants as reopening, despite being HTTP GET.
        jdbc.update("DELETE FROM role_permissions WHERE role_code='RECRUITER' AND permission_code='INTERVIEWS_READ_ALL'");
        try { error(call("GET", statusPath(interview, id), null, recruiterToken), 403, "FORBIDDEN"); }
        finally { jdbc.update("INSERT INTO role_permissions(role_code,permission_code) VALUES ('RECRUITER','INTERVIEWS_READ_ALL')"); }
        jdbc.update("UPDATE auth_sessions SET revoked_at=? WHERE user_id=?", Timestamp.from(ApplicationTestSupport.NOW), recruiter);
        assertThat(call("GET", statusPath(interview, id), null, recruiterToken).statusCode()).isEqualTo(401);
    }

    @Test
    void statusVersionBecomesStaleAfterAnotherReopenAndResubmission() throws Exception {
        UUID id = UUID.fromString(form(panelToken).path("id").asText());
        ok(write("POST", payload(0, 3, 4, "CONSIDER"), panelToken));
        JsonNode viewed = ok(call("GET", statusPath(interview, id), null, recruiterToken));
        JsonNode reopened = ok(reopen(id, viewed.path("version").asLong(), "Lần mở lại đầu", recruiterToken));
        ok(write("POST", payload(reopened.path("version").asLong(), 4, 5, "HIRE"), panelToken));
        error(reopen(id, viewed.path("version").asLong(), "Màn hình cũ", recruiterToken), 409, "EVALUATION_VERSION_CHANGED");
        JsonNode refreshed = ok(call("GET", statusPath(interview, id), null, recruiterToken));
        assertThat(refreshed.path("version").asLong()).isGreaterThan(viewed.path("version").asLong());
        ok(reopen(id, refreshed.path("version").asLong(), "Đã tải lại trạng thái", recruiterToken));
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
