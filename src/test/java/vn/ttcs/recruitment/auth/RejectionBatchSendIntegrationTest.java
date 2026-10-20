package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import vn.ttcs.recruitment.application.ApplicationSubmission;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** Task 527: the bounded bulk rejection send that checks every entry, skips the notified and never queues twice. */
@TestPropertySource(properties = "app.candidate-mail.poll-delay-ms=3600000")
class RejectionBatchSendIntegrationTest extends ScreeningTestSupport {
    private static final String REJECTIONS = "/api/v1/messaging/rejections";
    EmailTemplateTestData seeded;
    ApplicationTestSupport.Stored second;

    @BeforeEach void rejected() {
        seeded = new EmailTemplateTestData(jdbc);
        jdbc.execute("TRUNCATE candidate_access_events");
        UUID posting = jdbc.queryForObject("SELECT job_posting_id FROM job_applications WHERE id=?", UUID.class,
                first.applicationId());
        second = applications.stored(posting, new ApplicationSubmission("Trần Thị Bình", "binh@example.test",
                ApplicationTestSupport.PHONE, null, null));
        jdbc.update("UPDATE job_applications SET stage='REJECTED' WHERE id IN (?,?,?)", first.applicationId(),
                second.applicationId(), other.applicationId());
    }
    @AfterEach void restore() { seeded.restore(); }

    @Test
    void sendQueuesEachLetterOnceSkipsTheNotifiedAndReplaysTheSameRequest() throws Exception {
        // first already has an automatic letter on its way.
        jdbc.update("""
                INSERT INTO email_messages(id, template_type, application_id, trigger_kind, status, delivery, delivery_id,
                    recipient_email, dedupe_key, created_at, updated_at, sent_at)
                VALUES (gen_random_uuid(), 'REJECTION', ?, 'AUTOMATIC', 'SENT', 'CANDIDATE_MAIL', gen_random_uuid(),
                    'lan@example.test', 'REJECTION:AUTO:' || ?, now(), now(), now())
                """, first.applicationId(), first.applicationId());
        UUID request = UUID.randomUUID();
        String body = json.writeValueAsString(Map.of("requestId", request,
                "applicationIds", List.of(second.applicationId(), first.applicationId())));
        JsonNode result = ok(call("POST", REJECTIONS, body, recruiterToken));
        assertThat(result.path("queued").asInt()).isEqualTo(1);
        assertThat(result.path("skipped").asInt()).isEqualTo(1);
        Map<String, String> outcomes = new LinkedHashMap<>();
        result.path("entries").forEach(entry -> outcomes.put(entry.path("applicationId").asText(), entry.path("outcome").asText()));
        assertThat(outcomes).containsEntry(first.applicationId().toString(), "SKIPPED_ALREADY_NOTIFIED")
                .containsEntry(second.applicationId().toString(), "QUEUED");
        assertThat(jdbc.queryForMap("""
                SELECT m.trigger_kind, m.status, m.created_by, o.status AS queue FROM email_messages m
                JOIN candidate_mail_outbox o ON o.id = m.delivery_id WHERE m.application_id = ?
                """, second.applicationId())).containsEntry("trigger_kind", "MANUAL").containsEntry("status", "QUEUED")
                .containsEntry("created_by", recruiter).containsEntry("queue", "PENDING");

        // The same request again answers the same and queues nothing; the same id with another selection is 409.
        assertThat(ok(call("POST", REJECTIONS, body, recruiterToken))).isEqualTo(result);
        assertThat(count("candidate_mail_outbox")).isEqualTo(1);
        var reused = call("POST", REJECTIONS, json.writeValueAsString(Map.of("requestId", request,
                "applicationIds", List.of(second.applicationId()))), recruiterToken);
        assertThat(reused.statusCode()).isEqualTo(409);
        // A new request skips the letter queued just now.
        JsonNode later = ok(call("POST", REJECTIONS, json.writeValueAsString(Map.of("requestId", UUID.randomUUID(),
                "applicationIds", List.of(second.applicationId()))), hrToken));
        assertThat(later.path("entries").path(0).path("outcome").asText()).isEqualTo("SKIPPED_ALREADY_NOTIFIED");
    }

    @Test
    void concurrentBatchesSharingAnApplicationQueueItsLetterOnce() throws Exception {
        String hr = hrToken, recruiterToken2 = token(recruiter);
        List<Callable<HttpResponse<String>>> calls = new ArrayList<>();
        for (String token : List.of(hr, recruiterToken2, hr, recruiterToken2)) {
            String body = json.writeValueAsString(Map.of("requestId", UUID.randomUUID(),
                    "applicationIds", List.of(first.applicationId(), second.applicationId())));
            calls.add(() -> call("POST", REJECTIONS, body, token));
        }
        int queued = 0;
        try (var executor = Executors.newFixedThreadPool(4)) {
            for (Future<HttpResponse<String>> response : executor.invokeAll(calls)) {
                assertThat(response.get().statusCode()).as(response.get().body()).isEqualTo(200);
                queued += json.readTree(response.get().body()).path("queued").asInt();
            }
        }
        assertThat(queued).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_messages WHERE template_type='REJECTION'", Long.class))
                .isEqualTo(2);
    }

    @Test
    void aStaffRejectionAfterTheApplicationWasReopenedKeepsTheBulkLetterAndSucceeds() throws Exception {
        JsonNode sent = ok(call("POST", REJECTIONS, json.writeValueAsString(Map.of("requestId", UUID.randomUUID(),
                "applicationIds", List.of(second.applicationId()))), hrToken));
        assertThat(sent.path("queued").asInt()).isEqualTo(1);
        // The application is reopened (as an offer negotiation does from REJECTED) and a staff member rejects it again
        // while automatic rejection letters are on: the bulk letter stays the only one and the rejection succeeds.
        jdbc.update("UPDATE job_applications SET stage='NEW' WHERE id=?", second.applicationId());
        seeded.autoSend("REJECTION", true);
        var body = new LinkedHashMap<String, Object>();
        body.put("targetStage", "REJECTED"); body.put("expectedCurrentStage", "NEW");
        body.put("rejectionReasonId", catalog("REJECTION_REASON", "Chưa phù hợp", true));
        var rejected = call("POST", "/api/v1/applications/" + second.applicationId() + "/stage-changes",
                json.writeValueAsString(body), hrToken);
        assertThat(rejected.statusCode()).as(rejected.body()).isEqualTo(200);
        assertThat(jdbc.queryForList("SELECT trigger_kind FROM email_messages WHERE application_id=?"
                + " AND template_type='REJECTION'", String.class, second.applicationId())).containsExactly("MANUAL");
        assertThat(count("candidate_mail_outbox")).isEqualTo(1);
    }

    @Test
    void sendRefusesOutOfScopeOrNotRejectedApplicationsAndAFailedLetterWaitsForRetry() throws Exception {
        var outside = call("POST", REJECTIONS, json.writeValueAsString(Map.of("requestId", UUID.randomUUID(),
                "applicationIds", List.of(first.applicationId(), other.applicationId()))), recruiterToken);
        assertThat(outside.statusCode()).isEqualTo(403);
        assertThat(count("email_messages")).isZero();
        // A hiring manager reads but does not write candidates.
        assertThat(call("POST", REJECTIONS, json.writeValueAsString(Map.of("requestId", UUID.randomUUID(),
                "applicationIds", List.of(first.applicationId()))), token(manager)).statusCode()).isEqualTo(403);
        jdbc.update("""
                INSERT INTO email_messages(id, template_type, application_id, trigger_kind, status, delivery, delivery_id,
                    recipient_email, dedupe_key, last_failure, created_at, updated_at)
                VALUES (gen_random_uuid(), 'REJECTION', ?, 'AUTOMATIC', 'FAILED', 'CANDIDATE_MAIL', gen_random_uuid(),
                    'lan@example.test', 'REJECTION:AUTO:' || ?, 'RECIPIENT_REFUSED', now(), now())
                """, first.applicationId(), first.applicationId());
        JsonNode result = ok(call("POST", REJECTIONS, json.writeValueAsString(Map.of("requestId", UUID.randomUUID(),
                "applicationIds", List.of(first.applicationId()))), hrToken));
        assertThat(result.path("entries").path(0).path("outcome").asText()).isEqualTo("SKIPPED_RETRY_REQUIRED");
        jdbc.update("UPDATE job_applications SET stage='SCREENING' WHERE id=?", second.applicationId());
        var mixed = call("POST", REJECTIONS, json.writeValueAsString(Map.of("requestId", UUID.randomUUID(),
                "applicationIds", List.of(second.applicationId()))), hrToken);
        assertThat(mixed.statusCode()).isEqualTo(409);
    }
}
