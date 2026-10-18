package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.evaluationreport.EvaluationEvidenceService;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real HTTP, authorization and PostgreSQL evidence/decision atomicity, using disposable test data only. */
class HiringDecisionIntegrationTest extends InterviewEvaluationTestSupport {
    @Autowired EvaluationEvidenceService evidence;
    UUID rejectionReason;
    String managerToken;

    @BeforeEach
    void decisionFixture() {
        jdbc.update("UPDATE job_applications SET stage='INTERVIEW' WHERE id=?", first.applicationId());
        rejectionReason = catalog("REJECTION_REASON", "Chưa phù hợp kỹ năng", true);
        managerToken = token(manager);
    }

    @Test
    void departmentManagerDecidesWithoutReceivingGeneralPipelineWriteAndEvidenceIsFrozen() throws Exception {
        submitted(panelToken);
        var body = request("OFFER");
        body.put("note", "Phù hợp vị trí\r\nĐề xuất offer");
        JsonNode result = ok(decide(body, managerToken));
        assertThat(result.path("stage").asText()).isEqualTo("OFFER");
        assertThat(result.path("decidedById").asText()).isEqualTo(manager.toString());
        assertThat(result.path("decidedByName").asText()).isEqualTo("Manager");
        assertThat(result.path("note").asText()).isEqualTo("Phù hợp vị trí\nĐề xuất offer");
        assertThat(result.path("decidedAt").asText()).isEqualTo(ApplicationTestSupport.NOW.toString());
        assertThat(result.toString()).doesNotContain("Technical note", "totalScore", "email", "password");
        assertThat(stage()).isEqualTo("OFFER");
        assertThat(count("hiring_decisions")).isEqualTo(1);
        assertThat(count("application_stage_events")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hiring_decisions h JOIN application_stage_events e "
                + "ON e.id=h.stage_event_id WHERE e.application_id=h.application_id AND e.stage_before='INTERVIEW' "
                + "AND e.stage_after='OFFER' AND e.actor_id=h.actor_id AND e.occurred_at=h.decided_at", Long.class)).isEqualTo(1);
        JsonNode snapshot = json.readTree(jdbc.queryForObject("SELECT evidence_snapshot::text FROM hiring_decisions", String.class));
        assertThat(snapshot.get(0).path("totalScore").decimalValue()).isEqualByComparingTo("4.70");
        assertThat(snapshot.get(0).path("items").get(0).path("comment").asText()).isEqualTo("Technical note");
        assertThat(call("POST", "/api/v1/applications/" + first.applicationId() + "/stage-changes",
                "{\"targetStage\":\"HIRED\",\"expectedCurrentStage\":\"OFFER\"}", managerToken).statusCode()).isEqualTo(403);
    }

    @ParameterizedTest
    @ValueSource(strings = {"HR_MANAGER", "ADMIN"})
    void hrAndAdminMayDecideWithinTheirGrantedReadScope(String role) throws Exception {
        UUID actor = applications.account(role, role.toLowerCase(Locale.ROOT) + "@example.test", Role.valueOf(role));
        submitted(panelToken);
        ok(decide(request("OFFER"), token(actor)));
        assertThat(stage()).isEqualTo("OFFER");
    }

    @Test
    void rejectionUsesActiveCatalogReasonAndPreservesItsCurrentName() throws Exception {
        submitted(panelToken);
        var body = request("REJECT");
        body.put("rejectionReasonId", rejectionReason);
        JsonNode result = ok(decide(body, managerToken));
        assertThat(result.path("stage").asText()).isEqualTo("REJECTED");
        assertThat(result.path("rejectionReasonName").asText()).isEqualTo("Chưa phù hợp kỹ năng");
        jdbc.update("UPDATE recruitment_catalog_items SET name='Renamed after decision' WHERE id=?", rejectionReason);
        assertThat(jdbc.queryForObject("SELECT rejection_reason_name FROM hiring_decisions", String.class))
                .isEqualTo("Chưa phù hợp kỹ năng");
        assertThat(jdbc.queryForObject("SELECT rejection_reason_name FROM application_stage_events", String.class))
                .isEqualTo("Chưa phù hợp kỹ năng");
    }

    @Test
    void rejectionRequiresAnActiveReasonOfTheRightCatalogAndOfferRefusesOne() throws Exception {
        submitted(panelToken);
        var body = request("REJECT");
        error(decide(body, managerToken), 400, "REJECTION_REASON_REQUIRED");
        body.put("rejectionReasonId", UUID.randomUUID());
        error(decide(body, managerToken), 400, "INVALID_REJECTION_REASON");
        body.put("rejectionReasonId", tag);
        error(decide(body, managerToken), 400, "INVALID_REJECTION_REASON");
        jdbc.update("UPDATE recruitment_catalog_items SET active=false WHERE id=?", rejectionReason);
        body.put("rejectionReasonId", rejectionReason);
        error(decide(body, managerToken), 400, "REJECTION_REASON_INACTIVE");
        body.put("decision", "OFFER");
        error(decide(body, managerToken), 400, "REJECTION_REASON_NOT_ALLOWED");
        unchanged();
    }

    @Test
    void ordinaryRecruitersAndInterviewersCannotMakeDecisionsEvenWithReadAccess() throws Exception {
        submitted(panelToken);
        var body = request("OFFER");
        error(decide(body, recruiterToken), 403, "FORBIDDEN");
        error(decide(body, panelToken), 403, "FORBIDDEN");
        error(decide(body, null), 401, "UNAUTHORIZED");
        unchanged();
    }

    @Test
    void managerOfAnotherDepartmentIsDeniedEvenIfAssignedAsRecruiter() throws Exception {
        submitted(panelToken);
        UUID another = applications.account("Other Manager", "other-manager@example.test", Role.HIRING_MANAGER, Role.RECRUITER);
        jdbc.update("UPDATE departments SET manager_user_id=? WHERE id=(SELECT department_id FROM recruitment_requisitions WHERE id=?)",
                another, otherRequisition);
        applications.assign(requisition, another, "SUPPORTING", hr);
        error(decide(request("OFFER"), token(another)), 403, "FORBIDDEN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_access_events WHERE actor_id=? AND outcome='DENIED'",
                Long.class, another)).isEqualTo(1);
        unchanged();
    }

    @Test
    void submittedEvidenceIsRequiredAndAnOldRoundDoesNotAuthorizeANewerUnreviewedRound() throws Exception {
        error(decide(request("OFFER"), managerToken), 409, "HIRING_EVALUATION_REQUIRED");
        submitted(panelToken);
        newerInterview("SCHEDULED");
        error(decide(request("OFFER"), managerToken), 409, "HIRING_EVALUATION_REQUIRED");
        unchanged();
    }

    @Test
    void cancelledNewerRoundsDoNotReplaceTheLatestUsableRound() throws Exception {
        submitted(panelToken);
        newerInterview("CANCELLED");
        JsonNode result = ok(decide(request("OFFER"), managerToken));
        assertThat(result.path("interviewId").asText()).isEqualTo(interview.toString());
    }

    @Test
    void staleEvidenceIsRejectedAfterAFormIsReopenedAndNoOldSubmittedSnapshotIsReused() throws Exception {
        JsonNode form = submitted(panelToken);
        var original = request("OFFER");
        ok(call("POST", reopenPath(UUID.fromString(form.path("id").asText())),
                "{\"expectedVersion\":1,\"reason\":\"Cần bổ sung đánh giá\"}", recruiterToken));
        error(decide(original, managerToken), 409, "HIRING_EVIDENCE_CHANGED");
        error(decide(request("OFFER"), managerToken), 409, "HIRING_EVALUATION_REQUIRED");
        unchanged();
    }

    @Test
    void aPanelManagerMustSubmitTheirOwnCurrentFormBeforeUsingPeerEvidence() throws Exception {
        submitted(panelToken);
        var original = request("OFFER");
        jdbc.update("INSERT INTO interview_participants(interview_id,user_id) VALUES (?,?)", interview, manager);
        error(decide(original, managerToken), 403, "EVALUATION_BLIND_REVIEW");
        unchanged();
    }

    @Test
    void inactivePanelSubmissionsCannotJustifyADecision() throws Exception {
        submitted(panelToken);
        jdbc.update("UPDATE interview_participants SET active=false WHERE interview_id=? AND user_id=?", interview, panel);
        error(decide(request("OFFER"), managerToken), 409, "HIRING_EVALUATION_REQUIRED");
        unchanged();
    }

    @Test
    void staleStageAndInactiveRequisitionAreRejected() throws Exception {
        submitted(panelToken);
        var body = request("OFFER");
        jdbc.update("UPDATE job_applications SET stage='WITHDRAWN' WHERE id=?", first.applicationId());
        error(decide(body, managerToken), 409, "APPLICATION_STAGE_CHANGED");
        jdbc.update("UPDATE job_applications SET stage='INTERVIEW' WHERE id=?", first.applicationId());
        jdbc.update("UPDATE recruitment_requisitions SET status='PAUSED' WHERE id=?", requisition);
        error(decide(body, managerToken), 409, "HIRING_REQUISITION_INACTIVE");
        unchanged();
    }

    @Test
    void unknownOrForgedFieldsCannotChooseActorTimeStageOrScore() throws Exception {
        submitted(panelToken);
        for (String field : List.of("actorId", "decidedAt", "score", "targetStage")) {
            var body = request("OFFER"); body.put(field, "forged");
            assertThat(decide(body, managerToken).statusCode()).isEqualTo(400);
        }
        var body = request("HIRED");
        error(decide(body, managerToken), 400, "VALIDATION_ERROR");
        body = request("OFFER"); body.put("expectedEvidenceHash", "forged");
        error(decide(body, managerToken), 400, "VALIDATION_ERROR");
        body = request("OFFER"); body.put("note", "x".repeat(501));
        error(decide(body, managerToken), 400, "VALIDATION_ERROR");
        unchanged();
    }

    @Test
    void failingAuditInsertRollsBackTheStageAndItsHistory() throws Exception {
        submitted(panelToken);
        jdbc.execute("ALTER TABLE hiring_decisions ADD CONSTRAINT test_fail_hiring_insert CHECK (false) NOT VALID");
        try { assertThat(decide(request("OFFER"), managerToken).statusCode()).isEqualTo(500); }
        finally { jdbc.execute("ALTER TABLE hiring_decisions DROP CONSTRAINT test_fail_hiring_insert"); }
        unchanged();
    }

    @Test
    void decisionEvidenceCannotBeChangedOrDeletedAndDuplicateClickDoesNotAppendHistory() throws Exception {
        submitted(panelToken);
        var body = request("OFFER");
        ok(decide(body, managerToken));
        error(decide(body, managerToken), 409, "APPLICATION_STAGE_CHANGED");
        assertThatThrownBy(() -> jdbc.update("UPDATE hiring_decisions SET note='tamper'"))
                .rootCause().hasMessageContaining("hiring_decisions rows are never changed");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM hiring_decisions"))
                .rootCause().hasMessageContaining("hiring_decisions rows are never changed");
        assertThat(count("hiring_decisions")).isEqualTo(1);
        assertThat(count("application_stage_events")).isEqualTo(1);
    }

    @Test
    void competingOfferAndRejectionCommitExactlyOneDecision() throws Exception {
        submitted(panelToken);
        var offer = request("OFFER");
        var reject = request("REJECT"); reject.put("rejectionReasonId", rejectionReason);
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstDecision = executor.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return decide(offer, managerToken); });
            var secondDecision = executor.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return decide(reject, hrToken); });
            assertThat(List.of(firstDecision.get(20, TimeUnit.SECONDS).statusCode(), secondDecision.get(20, TimeUnit.SECONDS).statusCode()))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(count("hiring_decisions")).isEqualTo(1);
        assertThat(count("application_stage_events")).isEqualTo(1);
        assertThat(stage()).isIn("OFFER", "REJECTED");
    }

    @Test
    void revokedEvaluationReadDuringAnInterviewLockWaitPreventsTheDecision() throws Exception {
        submitted(panelToken);
        String body = json.writeValueAsString(request("OFFER"));
        try (Connection blocker = dataSource.getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try (var lock = blocker.prepareStatement("SELECT id FROM interview_sessions WHERE id=? FOR UPDATE")) {
                lock.setObject(1, interview); lock.executeQuery().close();
            }
            Future<HttpResponse<String>> pending;
            try {
                pending = executor.submit(() -> call("POST", decisionPath(), body, managerToken));
                applications.awaitWaiterOf(ApplicationTestSupport.backendPid(blocker));
                jdbc.update("DELETE FROM role_permissions WHERE role_code='HIRING_MANAGER' AND permission_code='EVALUATIONS_READ_SCOPED'");
            } finally { blocker.rollback(); }
            error(pending.get(10, TimeUnit.SECONDS), 403, "FORBIDDEN");
        } finally {
            jdbc.update("INSERT INTO role_permissions(role_code,permission_code) VALUES ('HIRING_MANAGER','EVALUATIONS_READ_SCOPED') ON CONFLICT DO NOTHING");
        }
        unchanged();
    }

    @Test
    void candidateWithdrawalThatCommitsFirstInvalidatesTheWaitingDecision() throws Exception {
        submitted(panelToken);
        String body = json.writeValueAsString(request("OFFER"));
        try (Connection blocker = dataSource.getConnection(); var executor = Executors.newFixedThreadPool(2)) {
            holdApplication(blocker);
            Future<HttpResponse<String>> withdrawal;
            Future<HttpResponse<String>> decision;
            try {
                withdrawal = executor.submit(() -> applications.withdraw(first.trackingCode(), "lan@example.test", null));
                int withdrawer = applications.awaitWaiterOf(ApplicationTestSupport.backendPid(blocker));
                decision = executor.submit(() -> call("POST", decisionPath(), body, managerToken));
                applications.awaitWaiterOf(withdrawer);
            } finally { blocker.rollback(); }
            assertThat(withdrawal.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            error(decision.get(10, TimeUnit.SECONDS), 409, "APPLICATION_STAGE_CHANGED");
        }
        assertThat(stage()).isEqualTo("WITHDRAWN");
        assertThat(count("hiring_decisions")).isZero();
        assertThat(count("application_stage_events")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT actor_type FROM application_stage_events", String.class)).isEqualTo("CANDIDATE");
    }

    @Test
    void aNewerInterviewCommittedBeforeTheDecisionInvalidatesItsReviewedEvidence() throws Exception {
        submitted(panelToken);
        String decisionBody = json.writeValueAsString(request("OFFER"));
        String scheduleBody = json.writeValueAsString(Map.of(
                "roundName", "New final round", "startsAt", ApplicationTestSupport.NOW.plusSeconds(7200).toString(),
                "durationMinutes", 60, "mode", "ONSITE", "room", "Final Scheduling Room",
                "participantIds", List.of(panel, peer)));
        try (Connection blocker = dataSource.getConnection(); var executor = Executors.newFixedThreadPool(2)) {
            holdApplication(blocker);
            Future<HttpResponse<String>> scheduling;
            Future<HttpResponse<String>> decision;
            try {
                scheduling = executor.submit(() -> call("POST", "/api/v1/applications/" + first.applicationId() + "/interviews",
                        scheduleBody, hrToken));
                int scheduler = applications.awaitWaiterOf(ApplicationTestSupport.backendPid(blocker));
                decision = executor.submit(() -> call("POST", decisionPath(), decisionBody, managerToken));
                applications.awaitWaiterOf(scheduler);
            } finally { blocker.rollback(); }
            var scheduled = scheduling.get(10, TimeUnit.SECONDS);
            assertThat(scheduled.statusCode()).as(scheduled.body()).isEqualTo(201);
            error(decision.get(10, TimeUnit.SECONDS), 409, "HIRING_EVIDENCE_CHANGED");
        }
        assertThat(count("interview_sessions")).isEqualTo(2);
        unchanged();
    }

    private void holdApplication(Connection connection) throws Exception {
        connection.setAutoCommit(false);
        try (var lock = connection.prepareStatement("SELECT id FROM job_applications WHERE id=? FOR UPDATE")) {
            lock.setObject(1, first.applicationId()); lock.executeQuery().close();
        }
    }

    private String decisionPath() { return "/api/v1/applications/" + first.applicationId() + "/hiring-decisions"; }
    private HttpResponse<String> decide(Map<String, Object> body, String token) throws Exception {
        return call("POST", decisionPath(), json.writeValueAsString(body), token);
    }
    private Map<String, Object> request(String decision) {
        String hash = new TransactionTemplate(transactions).execute(status -> evidence.load(first.applicationId(), hr, false).hash());
        var body = new LinkedHashMap<String, Object>();
        body.put("decision", decision); body.put("expectedCurrentStage", "INTERVIEW"); body.put("expectedEvidenceHash", hash);
        return body;
    }
    private JsonNode submitted(String token) throws Exception {
        form(token);
        return ok(call("POST", ownPath() + "/submit", json.writeValueAsString(payload(0, 4, 5, "HIRE")), token));
    }
    private UUID newerInterview(String status) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(ApplicationTestSupport.NOW);
        jdbc.update("""
                INSERT INTO interview_sessions(id,application_id,round_name,starts_at,ends_at,mode,room,normalized_room,
                    status,created_by,created_at,updated_at) VALUES (?,?,'Final round',?,?,'ONSITE','Final Room','final room',?,?,?,?)
                """, id, first.applicationId(), Timestamp.from(ApplicationTestSupport.NOW.plusSeconds(3600)),
                Timestamp.from(ApplicationTestSupport.NOW.plusSeconds(7200)), status, hr, now, now);
        return id;
    }
    private String stage() { return jdbc.queryForObject("SELECT stage FROM job_applications WHERE id=?", String.class, first.applicationId()); }
    private void unchanged() {
        assertThat(stage()).isEqualTo("INTERVIEW");
        assertThat(count("hiring_decisions")).isZero();
        assertThat(count("application_stage_events")).isZero();
    }
    private void error(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo(code);
    }
}
