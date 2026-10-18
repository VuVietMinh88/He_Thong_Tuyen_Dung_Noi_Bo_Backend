package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Public HTTP requests and disposable PostgreSQL: preserve legacy clients and immutable decline evidence. */
class OfferDeclineReasonIntegrationTest extends OfferDeliveryTestSupport {
    @ParameterizedTest
    @ValueSource(strings = {"SALARY", "LOCATION", "OTHER_JOB", "PERSONAL"})
    void structuredReasonIsStoredWithTheCompleteCandidateNoteAndOneStageEvent(String reason) throws Exception {
        var sent = queued();
        String note = "Ý kiến của ứng viên: " + "a".repeat(1000);

        var response = ok(respond(sent.token(), "DECLINE", note, reason));

        assertThat(response.path("status").asText()).isEqualTo("DECLINED");
        assertThat(state(sent.id())).isEqualTo("DECLINED");
        assertThat(stage()).isEqualTo("REJECTED");
        assertThat(storedReason(sent.id())).isEqualTo(reason);
        assertThat(jdbc.queryForObject("SELECT response_note FROM offers WHERE id=?", String.class, sent.id()))
            .isEqualTo(note);
        var event = jdbc.queryForMap("""
                SELECT comment,terms_snapshot->>'declineReason' AS reason
                FROM offer_events WHERE offer_id=? AND event_type='DECLINED'
                """, sent.id());
        assertThat(event.get("comment")).isEqualTo(note);
        assertThat(event.get("reason")).isEqualTo(reason);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_stage_events WHERE offer_id=?",
            Long.class, sent.id())).isEqualTo(1);
        assertThat(delivery(sent.id())).isEqualTo("CANCELLED");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void legacyOmittedAndExplicitNullReasonsRemainUnclassifiedAndRetryable(boolean explicitNull) throws Exception {
        var sent = queued();
        var response = explicitNull
            ? respond(sent.token(), "DECLINE", "Lý do riêng", null)
            : respond(sent.token(), "DECLINE", "Lý do riêng");

        var firstResponse = ok(response);

        assertThat(firstResponse.path("status").asText()).isEqualTo("DECLINED");
        assertThat(storedReason(sent.id())).isNull();
        assertThat(ok(respond(sent.token(), "DECLINE", "Lý do riêng"))).isEqualTo(firstResponse);
        assertThat(ok(respond(sent.token(), "DECLINE", "Lý do riêng", null))).isEqualTo(firstResponse);
        assertThat(jdbc.queryForObject("""
                SELECT terms_snapshot->'declineReason'='null'::jsonb
                FROM offer_events WHERE offer_id=? AND event_type='DECLINED'
                """, Boolean.class, sent.id())).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM offer_events WHERE offer_id=? AND event_type='DECLINED'",
            Long.class, sent.id())).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "salary", "OTHER", "OTHERJOB", "SALARY ", "<script>alert(1)</script>"})
    void unknownBlankOrNonCanonicalReasonsAreRejectedWithoutRecordingAnAnswer(String reason) throws Exception {
        var sent = queued();

        error(respond(sent.token(), "DECLINE", "Ghi chú", reason), 400, "OFFER_RESPONSE_VALIDATION");

        assertUnanswered(sent.id());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "true", "[]", "{}"})
    void nonStringReasonUsesTheExistingStrictJsonContract(String reasonJson) throws Exception {
        var sent = queued();
        String body = "{\"token\":\"" + sent.token() + "\",\"action\":\"DECLINE\",\"declineReason\":" + reasonJson + "}";

        error(call("POST", PUBLIC + "/respond", body, null), 400, "INVALID_JSON");

        assertUnanswered(sent.id());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SALARY", "LOCATION", "OTHER_JOB", "PERSONAL", "", " "})
    void acceptanceCannotCarryAnyNonNullDeclineReason(String reason) throws Exception {
        var sent = queued();

        error(respond(sent.token(), "ACCEPT", null, reason), 400, "OFFER_RESPONSE_VALIDATION");

        assertUnanswered(sent.id());
    }

    @Test
    void acceptanceWithExplicitNullReasonStillWorks() throws Exception {
        var sent = queued();

        var response = ok(respond(sent.token(), "ACCEPT", null, null));

        assertThat(response.path("status").asText()).isEqualTo("ACCEPTED");
        assertThat(stage()).isEqualTo("HIRED");
        assertThat(storedReason(sent.id())).isNull();
        assertThat(jdbc.queryForObject("""
                SELECT terms_snapshot IS NULL FROM offer_events WHERE offer_id=? AND event_type='ACCEPTED'
                """, Boolean.class, sent.id())).isTrue();
    }

    @Test
    void repeatedAnswerComparesNormalizedNoteAndExactReasonWithoutChangingEvidence() throws Exception {
        var sent = queued();
        var response = ok(respond(sent.token(), "DECLINE", "  Mức lương\r\nchưa phù hợp  ", "SALARY"));

        assertThat(ok(respond(sent.token(), "DECLINE", "Mức lương\nchưa phù hợp", "SALARY"))).isEqualTo(response);
        error(respond(sent.token(), "DECLINE", "Mức lương\nchưa phù hợp", "LOCATION"), 409, "OFFER_ALREADY_RESPONDED");
        error(respond(sent.token(), "DECLINE", "Mức lương\nchưa phù hợp", null), 409, "OFFER_ALREADY_RESPONDED");
        error(respond(sent.token(), "DECLINE", "Ý kiến khác", "SALARY"), 409, "OFFER_ALREADY_RESPONDED");

        assertThat(storedReason(sent.id())).isEqualTo("SALARY");
        assertThat(jdbc.queryForObject("SELECT version FROM offers WHERE id=?", Long.class, sent.id())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_stage_events WHERE offer_id=?",
            Long.class, sent.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM offer_events WHERE offer_id=? AND event_type='DECLINED'",
            Long.class, sent.id())).isEqualTo(1);
    }

    @Test
    void newOptionalFieldDoesNotPermitUnknownOrMisspelledFields() throws Exception {
        var sent = queued();
        var body = Map.of("token", sent.token(), "action", "DECLINE", "declineReason", "SALARY", "decline_reason", "LOCATION");

        error(call("POST", PUBLIC + "/respond", json.writeValueAsString(body), null), 400, "INVALID_JSON");

        assertUnanswered(sent.id());
    }

    @Test
    void failureToAppendStageEvidenceRollsBackReasonNoteStatusAndCandidateStage() throws Exception {
        var sent = queued();
        jdbc.execute("ALTER TABLE application_stage_events ADD CONSTRAINT test_decline_reason_rollback CHECK (offer_id IS NULL)");
        try {
            assertThat(respond(sent.token(), "DECLINE", "Không phù hợp", "SALARY").statusCode()).isEqualTo(500);
            assertUnanswered(sent.id());
            assertThat(jdbc.queryForObject("SELECT response_note IS NULL AND responded_at IS NULL FROM offers WHERE id=?",
                Boolean.class, sent.id())).isTrue();
            assertThat(delivery(sent.id())).isEqualTo("PENDING");
        } finally {
            jdbc.execute("ALTER TABLE application_stage_events DROP CONSTRAINT test_decline_reason_rollback");
        }
    }

    @Test
    void concurrentDifferentReasonsProduceOneAnswerAndCannotOverwriteEachOther() throws Exception {
        var sent = queued();
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var salary = executor.submit(() -> {
                barrier.await();
                return respond(sent.token(), "DECLINE", "Ý kiến", "SALARY");
            });
            var location = executor.submit(() -> {
                barrier.await();
                return respond(sent.token(), "DECLINE", "Ý kiến", "LOCATION");
            });
            assertThat(List.of(salary.get(15, TimeUnit.SECONDS).statusCode(), location.get(15, TimeUnit.SECONDS).statusCode()))
                .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(storedReason(sent.id())).isIn("SALARY", "LOCATION");
        assertThat(stage()).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_stage_events WHERE offer_id=?",
            Long.class, sent.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT o.decline_reason=e.terms_snapshot->>'declineReason'
                FROM offers o JOIN offer_events e ON e.offer_id=o.id AND e.event_type='DECLINED' WHERE o.id=?
                """, Boolean.class, sent.id())).isTrue();
    }

    @Test
    void candidateNoteRemainsDataAndIsNotReflectedIntoThePublicPageOrResponse() throws Exception {
        var sent = queued();
        String note = "<img src=x onerror=alert(1)> & \"nội dung của ứng viên\"";

        var response = respond(sent.token(), "DECLINE", note, "PERSONAL");

        ok(response);
        assertThat(jdbc.queryForObject("SELECT response_note FROM offers WHERE id=?", String.class, sent.id())).isEqualTo(note);
        assertThat(response.body()).doesNotContain(note, "<img");
        assertThat(lookup(sent.token()).body()).doesNotContain(note, "<img");
        var page = call("GET", PUBLIC + "/reply", null, null);
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).doesNotContain(note, "<img");
    }

    private HttpResponse<String> respond(String token, String action, String note, String reason) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("token", token);
        body.put("action", action);
        body.put("note", note);
        body.put("declineReason", reason);
        return call("POST", PUBLIC + "/respond", json.writeValueAsString(body), null);
    }

    private String storedReason(UUID id) {
        return jdbc.queryForObject("SELECT decline_reason FROM offers WHERE id=?", String.class, id);
    }

    private void assertUnanswered(UUID id) {
        assertThat(state(id)).isEqualTo("SENT");
        assertThat(stage()).isEqualTo("OFFER");
        assertThat(storedReason(id)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_stage_events WHERE offer_id=?", Long.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM offer_events WHERE offer_id=? AND event_type IN ('DECLINED','ACCEPTED')",
            Long.class, id)).isZero();
    }
}
