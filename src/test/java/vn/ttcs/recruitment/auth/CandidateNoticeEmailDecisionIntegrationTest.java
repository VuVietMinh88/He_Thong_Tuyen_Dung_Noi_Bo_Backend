package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import vn.ttcs.recruitment.messaging.CandidateNoticeEmails;

import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 533: the rejection letter and the thanks after an interview are decided by real events (a staff rejection, the
 * interview's first submitted evaluation) in the event's transaction, following each type's automatic-send flag.
 */
class CandidateNoticeEmailDecisionIntegrationTest extends InterviewEvaluationTestSupport {
    @Autowired CandidateNoticeEmails notices;
    EmailTemplateTestData seeded;
    UUID reason;

    @BeforeEach void flags() {
        seeded = new EmailTemplateTestData(jdbc);
        reason = catalog("REJECTION_REASON", "Chưa phù hợp", true);
    }
    @AfterEach void restore() { seeded.restore(); }

    @Test
    void staffRejectionQueuesTheLetterWhenAutomaticSendingIsOnAndSuppressesItOtherwise() throws Exception {
        seeded.autoSend("REJECTION", true);
        ok(reject(first.applicationId(), "NEW"));
        assertThat(messages("REJECTION")).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("application_id", first.applicationId()).containsEntry("status", "QUEUED")
                .containsEntry("delivery", "CANDIDATE_MAIL").containsEntry("trigger_kind", "AUTOMATIC")
                .containsEntry("recipient_email", "lan@example.test"));

        seeded.autoSend("REJECTION", false);
        ok(reject(other.applicationId(), "NEW"));
        assertThat(jdbc.queryForObject("SELECT status FROM email_messages WHERE application_id=?", String.class,
                other.applicationId())).isEqualTo("SUPPRESSED");
    }

    @Test
    void aRefusedStageChangeDecidesNoLetter() throws Exception {
        seeded.autoSend("REJECTION", true);
        HttpResponse<String> stale = reject(first.applicationId(), "SCREENING");
        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(count("email_messages")).isZero();
        assertThat(reject(first.applicationId(), "NEW").statusCode()).isEqualTo(200);
        // The application is now REJECTED (a final stage): no second rejection can be decided automatically.
        assertThat(reject(first.applicationId(), "REJECTED").statusCode()).isEqualTo(409);
        assertThat(messages("REJECTION")).hasSize(1);
    }

    @Test
    void theFirstSubmittedEvaluationDecidesOneThankYouPerInterview() throws Exception {
        seeded.autoSend("INTERVIEW_THANKS", true);
        submit(panelToken);
        assertThat(messages("INTERVIEW_THANKS")).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("interview_id", interview).containsEntry("status", "QUEUED")
                .containsEntry("delivery", "CANDIDATE_MAIL"));
        submit(peerToken);
        assertThat(messages("INTERVIEW_THANKS")).hasSize(1);
    }

    @Test
    void thanksWithAutomaticSendingOffIsSuppressedAndAWithdrawnOrDecidedCandidateGetsNone() throws Exception {
        submit(panelToken);
        assertThat(messages("INTERVIEW_THANKS")).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("status", "SUPPRESSED").containsEntry("delivery", null));

        jdbc.update("TRUNCATE email_messages CASCADE");
        seeded.autoSend("INTERVIEW_THANKS", true);
        // ONBOARDED needs a completed checklist (V64) and follows HIRED; HIRED stands for both final hiring stages.
        for (String stage : new String[] {"WITHDRAWN", "REJECTED", "HIRED"}) {
            jdbc.update("UPDATE job_applications SET stage=? WHERE id=?", stage, first.applicationId());
            new TransactionTemplate(transactions).executeWithoutResult(status ->
                    notices.interviewCompleted(interview, first.applicationId()));
            assertThat(count("email_messages")).as(stage).isZero();
        }
    }

    @Test
    void aStaffRejectionAfterTheApplicationWasReopenedKeepsTheLetterAlreadySent() throws Exception {
        // The candidate already received a rejection letter (sent in bulk) before the application was reopened.
        jdbc.update("""
                INSERT INTO email_messages(id, template_type, application_id, trigger_kind, status, delivery, delivery_id,
                    recipient_email, dedupe_key, created_at, updated_at, sent_at)
                VALUES (gen_random_uuid(), 'REJECTION', ?, 'MANUAL', 'SENT', 'CANDIDATE_MAIL', gen_random_uuid(),
                    'lan@example.test', 'REJECTION:MANUAL:' || ?, now(), now(), now())
                """, first.applicationId(), first.applicationId());
        seeded.autoSend("REJECTION", true);
        HttpResponse<String> rejected = reject(first.applicationId(), "NEW");
        assertThat(rejected.statusCode()).as(rejected.body()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT stage FROM job_applications WHERE id=?", String.class,
                first.applicationId())).isEqualTo("REJECTED");
        assertThat(messages("REJECTION")).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("trigger_kind", "MANUAL").containsEntry("status", "SENT"));
    }

    private HttpResponse<String> reject(UUID application, String expected) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("targetStage", "REJECTED"); body.put("expectedCurrentStage", expected); body.put("rejectionReasonId", reason);
        return call("POST", "/api/v1/applications/" + application + "/stage-changes", json.writeValueAsString(body), hrToken);
    }

    private void submit(String token) throws Exception {
        JsonNode form = form(token);
        var response = call("POST", ownPath() + "/submit",
                json.writeValueAsString(payload(form.path("version").asLong(), 4, 5, "HIRE")), token);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    }

    private java.util.List<Map<String, Object>> messages(String type) {
        return jdbc.queryForList("SELECT * FROM email_messages WHERE template_type=? ORDER BY created_at", type);
    }
}
