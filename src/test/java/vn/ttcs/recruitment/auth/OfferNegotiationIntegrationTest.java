package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Local HTTP and disposable PostgreSQL; these tests never call an SMTP sender. */
class OfferNegotiationIntegrationTest extends OfferDeliveryTestSupport {
    UUID position;

    @BeforeEach
    void positionBand() {
        position = jdbc.queryForObject("SELECT position_id FROM recruitment_requisitions WHERE id=?", UUID.class, requisition);
        jdbc.update("UPDATE positions SET salary_min=10000000,salary_max=30000000 WHERE id=?", position);
    }

    @Test
    void approvedOfferBecomesHistoricalAndRevisionMustBeApprovedAgain() throws Exception {
        UUID old = approved();
        JsonNode revised = created(revise(old, 0, recruiterToken));
        UUID next = UUID.fromString(revised.path("id").asText());
        assertThat(state(old)).isEqualTo("SUPERSEDED");
        assertThat(revised.path("status").asText()).isEqualTo("DRAFT");
        assertThat(revised.path("previousOfferId").asText()).isEqualTo(old.toString());
        assertThat(revised.path("revisionNumber").asInt()).isEqualTo(2);
        assertThat(revised.path("version").asInt()).isZero();
        assertThat(revised.path("approvalRound").asInt()).isZero();
        assertThat(revised.has("approvedAt")).isFalse();
        assertThat(revised.has("hrApproverId")).isFalse();
        assertThat(revised.has("submittedBy")).isFalse();
        JsonNode oldView = ok(call("GET", offerPath(old), null, recruiterToken));
        assertThat(oldView.path("replacedByOfferId").asText()).isEqualTo(next.toString());
        assertThat(oldView.path("salary").asLong()).isEqualTo(20_000_000);
        assertThat(revised.path("salary").asLong()).isEqualTo(22_000_000);
        error(send(next, 0, recruiterToken), 409, "OFFER_NOT_APPROVED");
        ok(call("POST", offerPath(next) + "/submit", json.writeValueAsString(Map.of("expectedVersion", 0, "hrApproverId", hr)), recruiterToken));
        ok(call("POST", offerPath(next) + "/approval", "{\"expectedVersion\":1,\"action\":\"APPROVE\"}", hrToken));
        assertThat(ok(send(next, 2, recruiterToken)).path("status").asText()).isEqualTo("SENT");
    }

    @Test
    void sentRevisionRevokesOldLinkAndErasesPendingMailAtomically() throws Exception {
        Sent sent = queued();
        created(revise(sent.id(), 1, recruiterToken));
        assertThat(state(sent.id())).isEqualTo("SUPERSEDED");
        assertThat(delivery(sent.id())).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT revoked_at IS NOT NULL FROM offer_response_tokens WHERE offer_id=?", Boolean.class, sent.id())).isTrue();
        assertThat(jdbc.queryForObject("SELECT ciphertext IS NULL AND nonce IS NULL AND key_id IS NULL FROM offer_delivery_outbox WHERE offer_id=?",
                Boolean.class, sent.id())).isTrue();
        assertThat(jdbc.queryForObject("SELECT sent_at IS NOT NULL FROM offers WHERE id=?", Boolean.class, sent.id())).isTrue();
        assertThat(lookup(sent.token()).statusCode()).isEqualTo(400);
        assertThat(respond(sent.token(), "ACCEPT", null).statusCode()).isEqualTo(400);
    }

    @Test
    void declinedRevisionPreservesCandidateEvidenceAndReopensOnlyThisApplication() throws Exception {
        Sent sent = declined("SALARY", "Need a different salary");
        JsonNode revised = created(revise(sent.id(), 2, recruiterToken));
        assertThat(stage()).isEqualTo("OFFER");
        assertThat(jdbc.queryForObject("SELECT stage FROM job_applications WHERE id=?", String.class, other.applicationId())).isEqualTo("NEW");
        JsonNode old = ok(call("GET", offerPath(sent.id()), null, recruiterToken));
        assertThat(old.path("status").asText()).isEqualTo("SUPERSEDED");
        assertThat(old.path("declineReason").asText()).isEqualTo("SALARY");
        assertThat(old.path("responseNote").asText()).isEqualTo("Need a different salary");
        assertThat(old.path("respondedAt").asText()).isEqualTo(NOW.plusSeconds(1).toString());
        assertThat(revised.has("declineReason")).isFalse();
        assertThat(revised.has("responseNote")).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_stage_events WHERE application_id=? AND actor_type='STAFF' AND stage_before='REJECTED' AND stage_after='OFFER' AND actor_id=?",
                Long.class, first.applicationId(), recruiter)).isEqualTo(1);
        assertThat(lookup(sent.token()).statusCode()).isEqualTo(400);
    }

    @Test
    void acceptedOfferIsNeverReplaced() throws Exception {
        Sent sent = queued();
        ok(respond(sent.token(), "ACCEPT", null));
        error(revise(sent.id(), 2, recruiterToken), 409, "OFFER_NOT_REVISABLE");
        assertThat(state(sent.id())).isEqualTo("ACCEPTED");
        assertThat(stage()).isEqualTo("HIRED");
        assertThat(count("offers")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "CHANGES_REQUESTED", "PENDING_HR", "PENDING_HIGHER", "REJECTED", "EXPIRED", "CANCELLED"})
    void otherStatusesCannotBeRevisedOrReceiveNegotiationNotes(String status) throws Exception {
        UUID old = approved();
        jdbc.update("UPDATE offers SET status=? WHERE id=?", status, old);
        error(revise(old, 0, recruiterToken), 409, "OFFER_NOT_REVISABLE");
        error(addNote(old, 0, "SALARY", "Discuss salary", recruiterToken), 409, "OFFER_NOT_REVISABLE");
        assertThat(count("offers")).isEqualTo(1);
        assertThat(count("offer_negotiations")).isZero();
    }

    @Test
    void revisionRejectsStaleVersionAndInvalidTermsWithoutTouchingSource() throws Exception {
        UUID old = approved();
        error(revise(old, 1, recruiterToken), 409, "OFFER_VERSION_CHANGED");
        for (String field : List.of("salary", "comment", "startDate", "responseDeadline", "status")) {
            var body = terms(0);
            switch (field) {
                case "salary" -> body.put("salary", 31_000_000);
                case "comment" -> body.put("comment", "   ");
                case "startDate" -> body.put("startDate", "2025-01-01");
                case "responseDeadline" -> body.put("responseDeadline", NOW.toString());
                case "status" -> body.put("status", "APPROVED");
            }
            assertThat(call("POST", offerPath(old) + "/revisions", json.writeValueAsString(body), recruiterToken).statusCode()).isEqualTo(400);
        }
        assertThat(state(old)).isEqualTo("APPROVED");
        assertThat(count("offers")).isEqualTo(1);
    }

    @Test
    void concurrentRevisionsHaveExactlyOneSuccessor() throws Exception {
        UUID old = approved();
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return revise(old, 0, recruiterToken); });
            var two = executor.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return revise(old, 0, hrToken); });
            assertThat(List.of(one.get(20, TimeUnit.SECONDS).statusCode(), two.get(20, TimeUnit.SECONDS).statusCode()))
                    .containsExactlyInAnyOrder(201, 409);
        }
        assertThat(count("offers")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM offers WHERE previous_offer_id=?", Long.class, old)).isEqualTo(1);
    }

    @Test
    void candidateAcceptanceAndRevisionCannotBothWin() throws Exception {
        Sent sent = queued();
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var revision = executor.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return revise(sent.id(), 1, recruiterToken); });
            var acceptance = executor.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return respond(sent.token(), "ACCEPT", null); });
            int revised = revision.get(20, TimeUnit.SECONDS).statusCode();
            int accepted = acceptance.get(20, TimeUnit.SECONDS).statusCode();
            if (accepted == 200) {
                assertThat(revised).isEqualTo(409);
                assertThat(state(sent.id())).isEqualTo("ACCEPTED");
                assertThat(stage()).isEqualTo("HIRED");
                assertThat(count("offers")).isEqualTo(1);
            } else {
                assertThat(revised).isEqualTo(201);
                assertThat(accepted).isEqualTo(400);
                assertThat(state(sent.id())).isEqualTo("SUPERSEDED");
                assertThat(stage()).isEqualTo("OFFER");
                assertThat(count("offers")).isEqualTo(2);
            }
        }
    }

    @Test
    void anotherApplicationEffectiveOfferRollsBackTheWholeRevisionIncludingReopen() throws Exception {
        Sent old = declined(null, "Legacy candidate comment");
        createOtherApplicationOffer();
        error(revise(old.id(), 2, recruiterToken), 409, "OFFER_ALREADY_ACTIVE");
        assertThat(state(old.id())).isEqualTo("DECLINED");
        assertThat(stage()).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_stage_events WHERE actor_type='STAFF' AND application_id=?", Long.class,
                first.applicationId())).isZero();
        assertThat(jdbc.queryForObject("SELECT revoked_at IS NULL FROM offer_response_tokens WHERE offer_id=?", Boolean.class, old.id())).isTrue();
        assertThat(count("offers")).isEqualTo(2);
    }

    @Test
    void laterStaffRejectionCannotBeReopenedByAnOlderCandidateDecline() throws Exception {
        Sent old = declined("PERSONAL", "Not now");
        UUID reason = catalog("REJECTION_REASON", "Staff decision", true);
        jdbc.update("""
                INSERT INTO application_stage_events(id,application_id,stage_before,stage_after,actor_type,actor_id,reason,occurred_at)
                VALUES (?,?,'REJECTED','OFFER','STAFF',?,'Reopened by staff',?)
                """, UUID.randomUUID(), first.applicationId(), hr, Timestamp.from(NOW.plusSeconds(2)));
        jdbc.update("""
                INSERT INTO application_stage_events(id,application_id,stage_before,stage_after,actor_type,actor_id,reason,occurred_at,rejection_reason_id,rejection_reason_name)
                VALUES (?,?,'OFFER','REJECTED','STAFF',?,'Later staff decision',?,?,'Staff decision')
                """, UUID.randomUUID(), first.applicationId(), hr, Timestamp.from(NOW.plusSeconds(3)), reason);
        error(revise(old.id(), 2, recruiterToken), 409, "OFFER_DECLINE_CONTEXT_CHANGED");
        JsonNode suggestion = ok(call("GET", offerPath(old.id()) + "/talent-pool-suggestion", null, recruiterToken));
        assertThat(suggestion.path("suggested").asBoolean()).isFalse();
        assertThat(suggestion.path("reason").asText()).isEqualTo("DECLINE_NOT_CURRENT");
        assertThat(stage()).isEqualTo("REJECTED");
    }

    @Test
    void ambiguousSameTimeStageHistoryAndWithdrawnParentFailClosed() throws Exception {
        Sent old = declined(null, null);
        jdbc.update("""
                INSERT INTO application_stage_events(id,application_id,stage_before,stage_after,actor_type,actor_id,occurred_at)
                VALUES (?,?,'SCREENING','INTERVIEW','STAFF',?,?)
                """, UUID.randomUUID(), first.applicationId(), hr, Timestamp.from(NOW.plusSeconds(1)));
        error(revise(old.id(), 2, recruiterToken), 409, "OFFER_DECLINE_CONTEXT_CHANGED");
        jdbc.update("UPDATE job_applications SET stage='WITHDRAWN' WHERE id=?", first.applicationId());
        error(revise(old.id(), 2, recruiterToken), 409, "OFFER_DECLINE_CONTEXT_CHANGED");
        assertThat(count("offers")).isEqualTo(1);
    }

    @Test
    void notesAreOrderedAppendOnlyAndDoNotInvalidateQueuedMailVersion() throws Exception {
        Sent sent = queued();
        created(addNote(sent.id(), 1, "SALARY", "First discussion", recruiterToken));
        created(addNote(sent.id(), 1, "LOCATION", "Second discussion", recruiterToken));
        JsonNode notes = ok(call("GET", offerPath(sent.id()) + "/negotiations?size=1", null, recruiterToken));
        assertThat(notes.path("totalElements").asInt()).isEqualTo(2);
        assertThat(notes.path("items").get(0).path("reason").asText()).isEqualTo("SALARY");
        JsonNode second = ok(call("GET", offerPath(sent.id()) + "/negotiations?page=1&size=1", null, recruiterToken));
        assertThat(second.path("items").get(0).path("reason").asText()).isEqualTo("LOCATION");
        assertThat(jdbc.queryForObject("SELECT version FROM offers WHERE id=?", Long.class, sent.id())).isEqualTo(1);
        assertThat(delivery(sent.id())).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT offer_version FROM offer_delivery_outbox WHERE offer_id=?", Long.class, sent.id())).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("UPDATE offer_negotiations SET comment='Rewritten' WHERE offer_id=?", sent.id()))
                .rootCause().hasMessageContaining("cannot be updated or deleted");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM offer_negotiations WHERE offer_id=?", sent.id()))
                .rootCause().hasMessageContaining("cannot be updated or deleted");
    }

    @Test
    void candidateDeclineReasonWinsOverInternalNotesAndSuggestionDoesNotMutateOffer() throws Exception {
        Sent old = declined("SALARY", "Candidate answer");
        created(addNote(old.id(), 2, "PERSONAL", "Staff follow-up", recruiterToken));
        long beforeEvents = count("offer_events");
        JsonNode suggestion = ok(call("GET", offerPath(old.id()) + "/talent-pool-suggestion", null, recruiterToken));
        assertThat(suggestion.path("suggested").asBoolean()).isTrue();
        assertThat(suggestion.path("reason").asText()).isEqualTo("ELIGIBLE_DECLINED");
        assertThat(suggestion.path("declineReason").asText()).isEqualTo("SALARY");
        assertThat(suggestion.path("declineComment").asText()).isEqualTo("Candidate answer");
        assertThat(suggestion.path("declineSource").asText()).isEqualTo("CANDIDATE");
        assertThat(count("offers")).isEqualTo(1);
        assertThat(count("offer_events")).isEqualTo(beforeEvents);
        assertThat(state(old.id())).isEqualTo("DECLINED");
        assertThat(stage()).isEqualTo("REJECTED");
    }

    @Test
    void legacyDeclineGetsLatestStaffCategoryWithoutRewritingOriginalEvidence() throws Exception {
        Sent old = declined(null, "Original legacy comment");
        created(addNote(old.id(), 2, "SALARY", "First reason", recruiterToken));
        created(addNote(old.id(), 2, "OTHER_JOB", "Latest reason", recruiterToken));
        JsonNode suggestion = ok(call("GET", offerPath(old.id()) + "/talent-pool-suggestion", null, recruiterToken));
        assertThat(suggestion.path("suggested").asBoolean()).isTrue();
        assertThat(suggestion.path("declineReason").asText()).isEqualTo("OTHER_JOB");
        assertThat(suggestion.path("declineComment").asText()).isEqualTo("Latest reason");
        assertThat(suggestion.path("declineSource").asText()).isEqualTo("STAFF");
        assertThat(jdbc.queryForObject("SELECT decline_reason FROM offers WHERE id=?", String.class, old.id())).isNull();
        assertThat(jdbc.queryForObject("SELECT response_note FROM offers WHERE id=?", String.class, old.id())).isEqualTo("Original legacy comment");
    }

    @Test
    void effectiveOfferForSameCandidateSuppressesSuggestion() throws Exception {
        Sent old = declined("LOCATION", "Commute");
        createOtherApplicationOffer();
        JsonNode suggestion = ok(call("GET", offerPath(old.id()) + "/talent-pool-suggestion", null, recruiterToken));
        assertThat(suggestion.path("suggested").asBoolean()).isFalse();
        assertThat(suggestion.path("reason").asText()).isEqualTo("CANDIDATE_HAS_EFFECTIVE_OFFER");
    }

    @Test
    void negotiationRequiresCurrentScopeAndStrictBoundedInput() throws Exception {
        UUID old = approved();
        String outsiderToken = token(outsider);
        error(addNote(old, 0, "SALARY", "No permission", outsiderToken), 403, "FORBIDDEN");
        error(revise(old, 0, outsiderToken), 403, "FORBIDDEN");
        error(call("GET", offerPath(old) + "/negotiations", null, outsiderToken), 403, "FORBIDDEN");
        error(call("GET", offerPath(old) + "/talent-pool-suggestion", null, outsiderToken), 403, "FORBIDDEN");
        for (String reason : List.of("OTHER", "salary", "")) {
            assertThat(addNote(old, 0, reason, "Text", recruiterToken).statusCode()).isEqualTo(400);
        }
        assertThat(addNote(old, 0, "SALARY", "   ", recruiterToken).statusCode()).isEqualTo(400);
        assertThat(addNote(old, 0, "SALARY", "x".repeat(2001), recruiterToken).statusCode()).isEqualTo(400);
        error(addNote(old, 1, "SALARY", "Stale", recruiterToken), 409, "OFFER_VERSION_CHANGED");
        error(call("GET", offerPath(old) + "/negotiations?size=101", null, recruiterToken), 400, "VALIDATION_ERROR");
        error(call("GET", offerPath(UUID.randomUUID()) + "/negotiations", null, recruiterToken), 404, "OFFER_NOT_FOUND");
        assertThat(count("offer_negotiations")).isZero();
    }

    @Test
    void managerReadAccessDoesNotPermitWritingAfterAssignmentRemoval() throws Exception {
        UUID old = approved();
        jdbc.update("UPDATE departments SET manager_user_id=? WHERE id=(SELECT department_id FROM recruitment_requisitions WHERE id=?)", recruiter, requisition);
        jdbc.update("DELETE FROM requisition_recruiters WHERE requisition_id=? AND recruiter_id=?", requisition, recruiter);
        ok(call("GET", offerPath(old) + "/negotiations", null, recruiterToken));
        error(addNote(old, 0, "SALARY", "No longer assigned", recruiterToken), 403, "FORBIDDEN");
        error(revise(old, 0, recruiterToken), 403, "FORBIDDEN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_access_events WHERE actor_id=? AND application_id=? AND outcome='DENIED'",
                Long.class, recruiter, first.applicationId())).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"session", "permission"})
    void revisionRechecksLiveAuthorityAfterPositionLockWait(String revoke) throws Exception {
        Sent sent = queued();
        try (Connection blocker = dataSource.getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try (var hold = blocker.prepareStatement("SELECT id FROM positions WHERE id=? FOR UPDATE")) {
                hold.setObject(1, position);
                hold.executeQuery().close();
            }
            var pending = executor.submit(() -> revise(sent.id(), 1, recruiterToken));
            try {
                applications.awaitWaiterOf(ApplicationTestSupport.backendPid(blocker));
                if (revoke.equals("session")) clock.set(NOW.plusSeconds(3600));
                else jdbc.update("DELETE FROM role_permissions WHERE role_code='RECRUITER' AND permission_code='OFFERS_WRITE_SCOPED'");
            } finally {
                blocker.rollback();
            }
            assertThat(pending.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(revoke.equals("session") ? 401 : 403);
        } finally {
            jdbc.update("INSERT INTO role_permissions(role_code,permission_code) VALUES('RECRUITER','OFFERS_WRITE_SCOPED') ON CONFLICT DO NOTHING");
        }
        assertThat(state(sent.id())).isEqualTo("SENT");
        assertThat(delivery(sent.id())).isEqualTo("PENDING");
        assertThat(count("offers")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT revoked_at IS NULL FROM offer_response_tokens WHERE offer_id=?", Boolean.class, sent.id())).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"session", "deadline"})
    void revisionRechecksAfterCrossApplicationCandidateUniqueIndexWait(String expiry) throws Exception {
        Sent source = declined(null, "Discuss again");
        JsonNode otherOffer = createOtherApplicationOffer();
        UUID otherId = UUID.fromString(otherOffer.path("id").asText());
        var request = terms(2);
        if (expiry.equals("deadline")) request.put("responseDeadline", NOW.plusSeconds(2).toString());
        String body = json.writeValueAsString(request);
        try (Connection blocker = dataSource.getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try (var update = blocker.prepareStatement("UPDATE offers SET status='CANCELLED' WHERE id=?")) {
                update.setObject(1, otherId);
                update.executeUpdate();
            }
            var pending = executor.submit(() -> call("POST", offerPath(source.id()) + "/revisions", body, recruiterToken));
            try {
                applications.awaitWaiterOf(ApplicationTestSupport.backendPid(blocker));
                clock.set(NOW.plusSeconds(expiry.equals("session") ? 3600 : 3));
                blocker.commit();
            } finally {
                blocker.rollback();
            }
            assertThat(pending.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(expiry.equals("session") ? 401 : 400);
        }
        assertThat(state(source.id())).isEqualTo("DECLINED");
        assertThat(stage()).isEqualTo("REJECTED");
        assertThat(count("offers")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM offers WHERE previous_offer_id=?", Long.class, source.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT revoked_at IS NULL FROM offer_response_tokens WHERE offer_id=?", Boolean.class, source.id())).isTrue();
    }

    private Sent declined(String reason, String note) throws Exception {
        Sent sent = queued();
        clock.set(NOW.plusSeconds(1));
        var body = new LinkedHashMap<String, Object>();
        body.put("token", sent.token());
        body.put("action", "DECLINE");
        body.put("note", note);
        if (reason != null) body.put("declineReason", reason);
        ok(call("POST", PUBLIC + "/respond", json.writeValueAsString(body), null));
        return sent;
    }

    private JsonNode createOtherApplicationOffer() throws Exception {
        jdbc.update("UPDATE job_applications SET stage='OFFER' WHERE id=?", other.applicationId());
        jdbc.update("UPDATE positions SET salary_min=10000000,salary_max=30000000 WHERE id=(SELECT position_id FROM recruitment_requisitions WHERE id=?)", otherRequisition);
        var body = terms(0);
        body.remove("expectedVersion");
        body.remove("comment");
        return created(call("POST", "/api/v1/applications/" + other.applicationId() + "/offers", json.writeValueAsString(body), hrToken));
    }

    private Map<String, Object> terms(long version) {
        var body = new LinkedHashMap<String, Object>();
        body.put("expectedVersion", version);
        body.put("salary", 22_000_000);
        body.put("allowance", 2_000_000);
        body.put("startDate", LocalDate.ofInstant(NOW, ZoneId.of("Asia/Ho_Chi_Minh")).plusDays(10).toString());
        body.put("responseDeadline", NOW.plusSeconds(2 * 86400).toString());
        body.put("comment", "Revised after discussion");
        return body;
    }

    private HttpResponse<String> revise(UUID id, long version, String actorToken) throws Exception {
        return call("POST", offerPath(id) + "/revisions", json.writeValueAsString(terms(version)), actorToken);
    }

    private HttpResponse<String> addNote(UUID id, long version, String reason, String comment, String actorToken) throws Exception {
        return call("POST", offerPath(id) + "/negotiations",
                json.writeValueAsString(Map.of("expectedVersion", version, "reason", reason, "comment", comment)), actorToken);
    }

    private JsonNode created(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(value -> assertThat(value).contains("no-store"));
        return json.readTree(response.body());
    }

    private static String offerPath(UUID id) {
        return "/api/v1/offers/" + id;
    }
}
