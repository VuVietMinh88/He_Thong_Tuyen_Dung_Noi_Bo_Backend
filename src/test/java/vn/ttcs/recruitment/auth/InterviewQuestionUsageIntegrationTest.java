package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;

import java.sql.Timestamp;
import java.sql.Connection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises real HTTP authorization, PostgreSQL snapshots and the independent same-application history. */
class InterviewQuestionUsageIntegrationTest extends InterviewEvaluationTestSupport {
    UUID technical, collaboration, inactive;

    @BeforeEach
    void questionsFixture() {
        technical = question(criterionA, "Explain transactions", "Atomicity and isolation", true);
        collaboration = question(criterionB, "Resolve a disagreement", "Listen and agree actions", true);
        inactive = question(criterionA, "Retired question", null, false);
    }

    @Test
    void listingRequiresExplicitFormAndNeverCreatesIt() throws Exception {
        error(call("GET", questions(), null, panelToken), 409, "QUESTION_EVALUATION_REQUIRED");
        assertThat(count("evaluation_forms")).isZero();
        assertThat(ok(call("GET", history(interview), null, panelToken)).path("totalElements").asLong()).isZero();
        form(panelToken);
        JsonNode result = ok(call("GET", questions(), null, panelToken));
        assertThat(result.path("totalElements").asLong()).isEqualTo(2);
        assertThat(result.toString()).contains("Atomicity and isolation", "Listen and agree actions")
                .doesNotContain("Retired question", "lan@example.test", "totalScore", "conclusion");
    }

    @Test
    void listingUsesOwnImmutableCriteriaSnapshotInsteadOfLaterPositionEdits() throws Exception {
        form(panelToken);
        jdbc.update("UPDATE competency_criteria SET name='Changed catalog name' WHERE id=?", criterionA);
        jdbc.update("UPDATE positions SET competency_framework_id=NULL WHERE id=?", position);
        JsonNode result = ok(call("GET", questions() + "?criterionId=" + criterionA, null, panelToken));
        assertThat(result.path("items").size()).isEqualTo(1);
        assertThat(result.path("items").get(0).path("criterionName").asText()).isEqualTo("Technical");
        assertThat(result.path("items").get(0).path("questionId").asText()).isEqualTo(technical.toString());
        assertThat(result.path("items").get(0).path("asked").asBoolean()).isFalse();
    }

    @Test
    void questionOutsideOwnCriteriaAndInactiveQuestionReturnSameNotFound() throws Exception {
        form(panelToken);
        UUID foreignCriterion = UUID.randomUUID();
        jdbc.update("INSERT INTO competency_criteria(id,framework_id,name,weight,sort_order) VALUES (?,?,'Other criterion',10,3)", foreignCriterion, framework);
        UUID foreignQuestion = question(foreignCriterion, "Not in frozen form", null, true);
        for (UUID id : new UUID[]{foreignQuestion, inactive, UUID.randomUUID()}) {
            error(mark(interview, id, panelToken, 0), 404, "INTERVIEW_QUESTION_NOT_FOUND");
        }
        assertThat(count("interview_question_usage")).isZero();
    }

    @Test
    void markIsSharedAcrossPanelAndRetryKeepsOriginalActorTimestampAndSnapshot() throws Exception {
        form(panelToken); form(peerToken);
        JsonNode firstMark = ok(mark(interview, technical, panelToken, 0));
        clock.set(ApplicationTestSupport.NOW.plusSeconds(30));
        JsonNode secondMark = ok(mark(interview, technical, peerToken, 0));
        assertThat(secondMark).isEqualTo(firstMark);
        assertThat(secondMark.path("askedBy").asText()).isEqualTo(panel.toString());
        assertThat(secondMark.path("askedAt").asText()).isEqualTo(ApplicationTestSupport.NOW.toString());
        assertThat(count("interview_question_usage")).isEqualTo(1);
        JsonNode row = ok(call("GET", questions() + "?criterionId=" + criterionA, null, peerToken)).path("items").get(0);
        assertThat(row.path("asked").asBoolean()).isTrue();
        assertThat(row.path("askedBy").asText()).isEqualTo(panel.toString());
    }

    @Test
    void askedSnapshotSurvivesQuestionEditMoveDeactivateAndDeletion() throws Exception {
        form(panelToken);
        JsonNode original = ok(mark(interview, technical, panelToken, 0));
        jdbc.update("UPDATE interview_questions SET content='Changed',answer_hint='Changed answer',criterion_id=?,active=false WHERE id=?", criterionB, technical);
        assertThat(ok(mark(interview, technical, panelToken, 0))).isEqualTo(original);
        jdbc.update("DELETE FROM interview_questions WHERE id=?", technical);
        JsonNode result = ok(call("GET", questions() + "?criterionId=" + criterionA, null, panelToken));
        assertThat(result.path("items").size()).isEqualTo(1);
        assertThat(result.path("items").get(0)).isEqualTo(original);
        assertThatThrownBy(() -> jdbc.update("UPDATE interview_question_usage SET content='tampered' WHERE interview_id=?", interview))
                .rootCause().hasMessageContaining("interview_question_usage rows are never changed");
    }

    @Test
    void nonInvitedAndRemovedPanelCannotProbeInterviewOrHistoryEvenWithHrGrants() throws Exception {
        form(panelToken);
        for (String path : new String[]{questions(), history(interview), "/api/v1/interviews/" + UUID.randomUUID() + "/questions"}) {
            error(call("GET", path, null, hrToken), 404, "INTERVIEW_NOT_FOUND");
        }
        error(mark(interview, technical, hrToken, 0), 404, "INTERVIEW_NOT_FOUND");
        jdbc.update("UPDATE interview_participants SET active=false WHERE interview_id=? AND user_id=?", interview, panel);
        error(call("GET", questions(), null, panelToken), 404, "INTERVIEW_NOT_FOUND");
        error(call("GET", history(interview), null, panelToken), 404, "INTERVIEW_NOT_FOUND");
        error(mark(interview, technical, panelToken, 0), 404, "INTERVIEW_NOT_FOUND");
        assertThat(count("interview_question_usage")).isZero();
    }

    @Test
    void everyRequiredPermissionIsCheckedFromCurrentDatabase() throws Exception {
        form(panelToken);
        for (String permission : new String[]{"INTERVIEWS_READ_SCOPED", "EVALUATIONS_READ_SCOPED", "EVALUATIONS_WRITE_SCOPED"}) {
            jdbc.update("DELETE FROM role_permissions WHERE role_code='INTERVIEWER' AND permission_code=?", permission);
            try {
                error(call("GET", questions(), null, panelToken), 403, "FORBIDDEN");
                error(call("GET", history(interview), null, panelToken), 403, "FORBIDDEN");
                error(mark(interview, technical, panelToken, 0), 403, "FORBIDDEN");
            } finally {
                jdbc.update("INSERT INTO role_permissions(role_code,permission_code) VALUES ('INTERVIEWER',?) ON CONFLICT DO NOTHING", permission);
            }
        }
    }

    @Test
    void revokedSessionAndAnonymousCallerCannotReadOrWrite() throws Exception {
        form(panelToken);
        assertThat(call("GET", questions(), null, null).statusCode()).isEqualTo(401);
        jdbc.update("UPDATE auth_sessions SET revoked_at=? WHERE user_id=?", Timestamp.from(ApplicationTestSupport.NOW), panel);
        assertThat(call("GET", questions(), null, panelToken).statusCode()).isEqualTo(401);
        assertThat(mark(interview, technical, panelToken, 0).statusCode()).isEqualTo(401);
    }

    @Test
    void cancelledInterviewAndChangedVersionNeverWriteUsage() throws Exception {
        form(panelToken);
        jdbc.update("UPDATE interview_sessions SET version=1 WHERE id=?", interview);
        error(mark(interview, technical, panelToken, 0), 409, "INTERVIEW_VERSION_CHANGED");
        jdbc.update("UPDATE interview_sessions SET status='CANCELLED' WHERE id=?", interview);
        error(mark(interview, technical, panelToken, 1), 409, "INTERVIEW_CANCELLED");
        error(call("GET", questions(), null, panelToken), 409, "INTERVIEW_CANCELLED");
        assertThat(count("interview_question_usage")).isZero();
    }

    @Test
    void rejectedWithdrawnHiredOrClosedRequisitionCannotBeMarked() throws Exception {
        form(panelToken);
        for (String stage : new String[]{"REJECTED", "WITHDRAWN", "HIRED"}) {
            jdbc.update("UPDATE job_applications SET stage=? WHERE id=?", stage, first.applicationId());
            error(mark(interview, technical, panelToken, 0), 409, "INTERVIEW_APPLICATION_INACTIVE");
        }
        jdbc.update("UPDATE job_applications SET stage='NEW' WHERE id=?", first.applicationId());
        jdbc.update("UPDATE recruitment_requisitions SET status='PAUSED' WHERE id=?", requisition);
        error(mark(interview, technical, panelToken, 0), 409, "INTERVIEW_APPLICATION_INACTIVE");
        assertThat(count("interview_question_usage")).isZero();
    }

    @Test
    void submittedOwnFormAndReopenedFormKeepQuestionEvidenceLocked() throws Exception {
        JsonNode own = form(panelToken);
        ok(mark(interview, technical, panelToken, 0));
        ok(call("POST", ownPath() + "/submit", json.writeValueAsString(payload(0, 3, 4, "HIRE")), panelToken));
        error(mark(interview, collaboration, panelToken, 0), 409, "EVALUATION_LOCKED");
        UUID formId = UUID.fromString(own.path("id").asText());
        ok(call("POST", reopenPath(formId), json.writeValueAsString(Map.of("expectedVersion", 1, "reason", "Clarify score")), recruiterToken));
        error(mark(interview, collaboration, panelToken, 0), 409, "EVALUATION_LOCKED");
        assertThat(count("interview_question_usage")).isEqualTo(1);
        assertThat(ok(call("GET", questions(), null, panelToken)).path("totalElements").asLong()).isEqualTo(2);
    }

    @Test
    void onboardedApplicationCannotRecordAnyFurtherInterviewQuestion() throws Exception {
        form(panelToken);
        applications.stage(first.applicationId(), vn.ttcs.recruitment.application.ApplicationStage.ONBOARDED);
        error(mark(interview, technical, panelToken, 0), 409, "INTERVIEW_APPLICATION_INACTIVE");
        assertThat(count("interview_question_usage")).isZero();
    }

    @Test
    void historyShowsOnlyPriorRoundsOfSameApplicationNeverOtherApplicationOfSameCandidate() throws Exception {
        form(panelToken); ok(mark(interview, technical, panelToken, 0));
        UUID next = anotherInterview(first.applicationId(), "Next round", 3600, 7200);
        UUID differentApplication = anotherInterview(other.applicationId(), "Other application", -10000, -9000);
        insertHistoricalUsage(differentApplication, other.applicationId(), "Must stay private");
        JsonNode history = ok(call("GET", history(next), null, panelToken));
        assertThat(history.path("totalElements").asLong()).isEqualTo(1);
        assertThat(history.path("items").get(0).path("interviewId").asText()).isEqualTo(interview.toString());
        assertThat(history.toString()).contains("Explain transactions").doesNotContain("Must stay private");
        assertThat(ok(call("GET", history(interview), null, panelToken)).path("totalElements").asLong()).isZero();
    }

    @Test
    void overlappingFutureAndCancelledRoundsAreExcludedFromPreviousRoundHistory() throws Exception {
        form(panelToken); ok(mark(interview, technical, panelToken, 0));
        UUID next = anotherInterview(first.applicationId(), "Next round", 3600, 7200);
        UUID overlap = anotherInterview(first.applicationId(), "Overlapping round", 3000, 5000);
        UUID future = anotherInterview(first.applicationId(), "Future round", 8000, 9000);
        UUID cancelled = anotherInterview(first.applicationId(), "Cancelled round", -10000, -9000);
        for (UUID id : new UUID[]{overlap, future, cancelled}) insertHistoricalUsage(id, first.applicationId(), "Excluded history");
        jdbc.update("UPDATE interview_sessions SET status='CANCELLED' WHERE id=?", cancelled);
        JsonNode result = ok(call("GET", history(next), null, panelToken));
        assertThat(result.path("totalElements").asLong()).isEqualTo(1);
        assertThat(result.toString()).doesNotContain("Excluded history");
    }

    @Test
    void historyKeepsOriginalRoundAndContentAndSupportsCriterionFilter() throws Exception {
        form(panelToken); ok(mark(interview, technical, panelToken, 0));
        UUID next = anotherInterview(first.applicationId(), "Next round", 3600, 7200);
        jdbc.update("UPDATE interview_sessions SET round_name='Changed round' WHERE id=?", interview);
        jdbc.update("DELETE FROM interview_questions WHERE id=?", technical);
        JsonNode result = ok(call("GET", history(next) + "?criterionId=" + criterionA, null, panelToken));
        assertThat(result.path("items").get(0).path("roundName").asText()).isEqualTo("Technical round");
        assertThat(result.path("items").get(0).path("question").path("answerHint").asText()).isEqualTo("Atomicity and isolation");
        assertThat(ok(call("GET", history(next) + "?criterionId=" + criterionB, null, panelToken)).path("totalElements").asLong()).isZero();
    }

    @Test
    void paginationIsStableBoundedAndUnknownCriterionIsAnEmptyFilter() throws Exception {
        form(panelToken);
        JsonNode firstPage = ok(call("GET", questions() + "?size=1", null, panelToken));
        JsonNode secondPage = ok(call("GET", questions() + "?size=1&page=1", null, panelToken));
        assertThat(firstPage.path("totalPages").asLong()).isEqualTo(2);
        assertThat(firstPage.path("items").get(0).path("questionId").asText()).isNotEqualTo(secondPage.path("items").get(0).path("questionId").asText());
        assertThat(ok(call("GET", questions() + "?criterionId=" + UUID.randomUUID(), null, panelToken)).path("items").size()).isZero();
        for (String suffix : new String[]{"?page=-1", "?page=100001", "?size=0", "?size=101"}) {
            error(call("GET", questions() + suffix, null, panelToken), 400, "VALIDATION_ERROR");
            error(call("GET", history(interview) + suffix, null, panelToken), 400, "VALIDATION_ERROR");
        }
    }

    @Test
    void markRejectsUnknownFieldsStringFractionNullOverflowAndNegativeVersions() throws Exception {
        form(panelToken);
        for (String request : new String[]{"{}", "null", "[]", "{\"expectedVersion\":null}", "{\"expectedVersion\":\"0\"}",
                "{\"expectedVersion\":0.5}", "{\"expectedVersion\":-1}", "{\"expectedVersion\":9223372036854775808}",
                "{\"expectedVersion\":0,\"askedBy\":\"forged\"}"}) {
            assertThat(call("POST", asked(interview, technical), request, panelToken).statusCode()).as(request).isEqualTo(400);
        }
        assertThat(count("interview_question_usage")).isZero();
    }

    @Test
    void simultaneousPanelMarksCreateOneSharedImmutableRecord() throws Exception {
        form(panelToken); form(peerToken);
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstMark = executor.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return mark(interview, technical, panelToken, 0); });
            var secondMark = executor.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return mark(interview, technical, peerToken, 0); });
            JsonNode a = ok(firstMark.get(20, TimeUnit.SECONDS));
            JsonNode b = ok(secondMark.get(20, TimeUnit.SECONDS));
            assertThat(a).isEqualTo(b);
            assertThat(a.path("askedBy").asText()).isIn(panel.toString(), peer.toString());
        }
        assertThat(count("interview_question_usage")).isEqualTo(1);
    }

    @ParameterizedTest(name = "{0} while waiting for {1}")
    @CsvSource({"permission,interview", "permission,question", "expiry,interview", "expiry,question"})
    void permissionAndTokenAreRecheckedAfterBlockingRows(String change, String blocked) throws Exception {
        form(panelToken); ok(call("GET", questions(), null, panelToken));
        try (Connection blocker = dataSource.getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            hold(blocker, blocked.equals("interview") ? "interview_sessions" : "interview_questions",
                    blocked.equals("interview") ? interview : technical);
            var pending = executor.submit(() -> mark(interview, technical, panelToken, 0));
            try {
                applications.awaitWaiterOf(ApplicationTestSupport.backendPid(blocker));
                if (change.equals("permission")) {
                    jdbc.update("DELETE FROM role_permissions WHERE role_code='INTERVIEWER' AND permission_code='EVALUATIONS_WRITE_SCOPED'");
                } else clock.set(ApplicationTestSupport.NOW.plusSeconds(3600));
            } finally { blocker.rollback(); }
            assertThat(pending.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(change.equals("permission") ? 403 : 401);
        } finally {
            jdbc.update("INSERT INTO role_permissions(role_code,permission_code) VALUES ('INTERVIEWER','EVALUATIONS_WRITE_SCOPED') ON CONFLICT DO NOTHING");
            clock.set(ApplicationTestSupport.NOW);
        }
        assertThat(count("interview_question_usage")).isZero();
    }

    @Test
    void applicationRejectionCommittedDuringLockWaitPreventsMark() throws Exception {
        form(panelToken); ok(call("GET", questions(), null, panelToken));
        try (Connection blocker = dataSource.getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            hold(blocker, "job_applications", first.applicationId());
            var pending = executor.submit(() -> mark(interview, technical, panelToken, 0));
            try {
                applications.awaitWaiterOf(ApplicationTestSupport.backendPid(blocker));
                try (var update = blocker.prepareStatement("UPDATE job_applications SET stage='REJECTED' WHERE id=?")) {
                    update.setObject(1, first.applicationId()); update.executeUpdate();
                }
                blocker.commit();
            } finally { blocker.rollback(); }
            error(pending.get(10, TimeUnit.SECONDS), 409, "INTERVIEW_APPLICATION_INACTIVE");
        }
        assertThat(count("interview_question_usage")).isZero();
    }

    private UUID question(UUID criterion, String content, String hint, boolean active) {
        UUID id = UUID.randomUUID(); Timestamp now = Timestamp.from(ApplicationTestSupport.NOW);
        jdbc.update("INSERT INTO interview_questions(id,criterion_id,content,difficulty,answer_hint,active,created_at,updated_at) VALUES (?,?,?,'MEDIUM',?,?,?,?)",
                id, criterion, content, hint, active, now, now); return id;
    }
    private void hold(Connection connection, String table, UUID id) throws Exception {
        connection.setAutoCommit(false);
        try (var query = connection.prepareStatement("SELECT id FROM " + table + " WHERE id=? FOR UPDATE")) {
            query.setObject(1, id); query.executeQuery().close();
        }
    }
    private UUID anotherInterview(UUID application, String round, long startsAfter, long endsAfter) {
        UUID id = UUID.randomUUID(); Timestamp now = Timestamp.from(ApplicationTestSupport.NOW);
        jdbc.update("""
                INSERT INTO interview_sessions(id,application_id,round_name,starts_at,ends_at,mode,room,normalized_room,created_by,created_at,updated_at)
                VALUES (?,?,?,?,?,'ONSITE','Room','room',?,?,?)
                """, id, application, round, Timestamp.from(ApplicationTestSupport.NOW.plusSeconds(startsAfter)),
                Timestamp.from(ApplicationTestSupport.NOW.plusSeconds(endsAfter)), hr, now, now);
        jdbc.update("INSERT INTO interview_participants(interview_id,user_id) VALUES (?,?)", id, panel);
        return id;
    }
    private void insertHistoricalUsage(UUID id, UUID application, String content) {
        jdbc.update("""
                INSERT INTO interview_question_usage(interview_id,application_id,question_id,criterion_id,criterion_name,content,
                    difficulty,round_name,interview_starts_at,asked_by,asked_at)
                SELECT id,?,?,?,?,?,'MEDIUM',round_name,starts_at,?,? FROM interview_sessions WHERE id=?
                """, application, UUID.randomUUID(), criterionA, "Technical", content, panel, Timestamp.from(ApplicationTestSupport.NOW), id);
    }
    private String questions() { return "/api/v1/interviews/" + interview + "/questions"; }
    private String history(UUID id) { return "/api/v1/interviews/" + id + "/question-history"; }
    private String asked(UUID id, UUID question) { return "/api/v1/interviews/" + id + "/questions/" + question + "/asked"; }
    private java.net.http.HttpResponse<String> mark(UUID id, UUID question, String token, long version) throws Exception {
        return call("POST", asked(id, question), json.writeValueAsString(Map.of("expectedVersion", version)), token);
    }
    private void error(java.net.http.HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo(code);
    }
}
