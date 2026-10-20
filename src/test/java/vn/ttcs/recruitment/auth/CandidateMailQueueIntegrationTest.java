package vn.ttcs.recruitment.auth;

import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import vn.ttcs.recruitment.messaging.CandidateMailFormat;
import vn.ttcs.recruitment.messaging.CandidateMailSettings;
import vn.ttcs.recruitment.messaging.CandidateMailWorker;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 538: rejection letters and thank-you emails go through the durable candidate mail queue: queued in the business
 * transaction, sent after commit by the worker over a loopback SMTP server, with retries, current-state checks and the
 * attempt log of task 537.
 */
@TestPropertySource(properties = "app.candidate-mail.poll-delay-ms=3600000")
class CandidateMailQueueIntegrationTest extends InterviewEvaluationTestSupport {
    private static final LocalSmtpServer SMTP = smtpServer();
    @Autowired CandidateMailWorker worker;
    @Autowired CandidateMailSettings mailSettings;
    EmailTemplateTestData seeded;
    UUID reason;

    @DynamicPropertySource static void smtp(DynamicPropertyRegistry values) {
        values.add("spring.mail.host", () -> "127.0.0.1");
        values.add("spring.mail.port", SMTP::port);
        values.add("spring.mail.properties.mail.smtp.auth", () -> false);
        values.add("spring.mail.properties.mail.smtp.starttls.enable", () -> false);
        values.add("spring.mail.properties.mail.smtp.starttls.required", () -> false);
        values.add("spring.mail.properties.mail.smtp.ssl.enable", () -> false);
    }
    @AfterAll static void closeSmtp() throws IOException { SMTP.close(); }
    private static LocalSmtpServer smtpServer() {
        try { return new LocalSmtpServer(); } catch (IOException failure) { throw new IllegalStateException(failure); }
    }

    @BeforeEach void queue() {
        SMTP.reset();
        seeded = new EmailTemplateTestData(jdbc);
        reason = catalog("REJECTION_REASON", "Chưa phù hợp", true);
    }
    @AfterEach void restore() { seeded.restore(); }

    @Test
    void rejectionIsQueuedWithTheStageChangeAndSentAfterCommitWithTheTemplate() throws Exception {
        seeded.autoSend("REJECTION", true);
        jdbc.update("UPDATE email_templates SET subject = 'Kết quả vị trí {{positionTitle}}' WHERE template_type = 'REJECTION'");
        ok(reject(first.applicationId()));
        // The stage change answered without waiting for SMTP; the email waits in the queue.
        assertThat(SMTP.messages()).isEmpty();
        var queued = jdbc.queryForMap("""
                SELECT o.id, o.status, m.status AS message_status, m.delivery_id FROM candidate_mail_outbox o
                JOIN email_messages m ON m.id = o.message_id WHERE m.application_id = ?
                """, first.applicationId());
        assertThat(queued).containsEntry("status", "PENDING").containsEntry("message_status", "QUEUED");
        assertThat(queued.get("delivery_id")).isEqualTo(queued.get("id"));

        assertThat(worker.processNext()).isTrue();
        assertThat(worker.processNext()).isFalse();
        MimeMessage mail = SMTP.messages().getFirst();
        assertThat(mail.getAllRecipients()[0].toString()).isEqualTo("lan@example.test");
        assertThat(mail.getSubject()).isEqualTo("Kết quả vị trí Java");
        assertThat(mail.getMessageID()).isEqualTo(mailSettings.messageId((UUID) queued.get("id")));
        assertThat(CandidateEmailTemplateDeliveryIntegrationTest.plain(mail))
                .contains("Xin chào " + ApplicationTestSupport.FULL_NAME + ",", "ứng tuyển vị trí Java", "Cần hỗ trợ?");
        assertThat(jdbc.queryForObject("SELECT status FROM email_messages WHERE application_id=?", String.class,
                first.applicationId())).isEqualTo("SENT");
        assertThat(jdbc.queryForList("SELECT result FROM email_attempts WHERE error_code IS NULL", String.class))
                .containsExactly("SENT");
        assertThat(count("email_attempts")).isEqualTo(1);
    }

    @Test
    void thankYouUsesTheInterviewTimeAndATemporaryFailureIsRetried() throws Exception {
        seeded.autoSend("INTERVIEW_THANKS", true);
        submit(panelToken);
        SMTP.rejectDelivery(true);
        assertThat(worker.processNext()).isTrue();
        assertThat(jdbc.queryForMap("SELECT status, last_failure FROM candidate_mail_outbox"))
                .containsEntry("status", "RETRY_WAIT").containsEntry("last_failure", "SMTP_TEMPORARY");
        assertThat(jdbc.queryForObject("SELECT status FROM email_messages", String.class)).isEqualTo("RETRY_WAIT");
        SMTP.rejectDelivery(false);
        jdbc.update("UPDATE candidate_mail_outbox SET next_attempt_at = clock_timestamp() - INTERVAL '1 second'");
        assertThat(worker.processNext()).isTrue();
        String time = CandidateMailFormat.interviewTime(ApplicationTestSupport.NOW.minusSeconds(5400), ZoneId.of("Asia/Ho_Chi_Minh"));
        assertThat(CandidateEmailTemplateDeliveryIntegrationTest.plain(SMTP.messages().getFirst())).contains("lúc " + time);
        assertThat(jdbc.queryForList("SELECT result FROM email_attempts ORDER BY attempt_number", String.class))
                .containsExactly("RETRY_SCHEDULED", "SENT");
    }

    @Test
    void aChangedAddressOrAnUndoneStateCancelsTheEmailBeforeSmtp() throws Exception {
        seeded.autoSend("REJECTION", true);
        seeded.autoSend("INTERVIEW_THANKS", true);
        ok(reject(first.applicationId()));
        jdbc.update("UPDATE job_applications SET email='moved@example.test', normalized_email='moved@example.test' WHERE id=?",
                first.applicationId());
        assertThat(worker.processNext()).isTrue();
        assertThat(jdbc.queryForMap("SELECT status, last_failure FROM email_messages WHERE application_id=?", first.applicationId()))
                .containsEntry("status", "CANCELLED").containsEntry("last_failure", "RECIPIENT_UNAVAILABLE");

        // The thank-you is decided while the application is still in its interview stage; a final decision taken
        // before the worker runs means it is not sent.
        jdbc.update("UPDATE job_applications SET stage='INTERVIEW', email='lan@example.test', normalized_email='lan@example.test' WHERE id=?",
                first.applicationId());
        submit(panelToken);
        jdbc.update("UPDATE job_applications SET stage='REJECTED' WHERE id=?", first.applicationId());
        assertThat(worker.processNext()).isTrue();
        assertThat(jdbc.queryForObject("SELECT last_failure FROM email_messages WHERE template_type='INTERVIEW_THANKS'",
                String.class)).isEqualTo("OBSOLETE");
        assertThat(SMTP.messages()).isEmpty();
        assertThat(jdbc.queryForList("SELECT result FROM email_attempts", String.class)).containsOnly("CANCELLED");
    }

    @Test
    void aRefusedStageChangeQueuesNothingAndSuppressedEmailsAreNeverQueued() throws Exception {
        seeded.autoSend("REJECTION", true);
        var body = new LinkedHashMap<String, Object>();
        body.put("targetStage", "REJECTED"); body.put("expectedCurrentStage", "OFFER"); body.put("rejectionReasonId", reason);
        assertThat(call("POST", "/api/v1/applications/" + first.applicationId() + "/stage-changes",
                json.writeValueAsString(body), hrToken).statusCode()).isEqualTo(409);
        assertThat(count("candidate_mail_outbox")).isZero();
        seeded.autoSend("REJECTION", false);
        ok(reject(first.applicationId()));
        assertThat(count("candidate_mail_outbox")).isZero();
        assertThat(worker.processNext()).isFalse();
    }

    private HttpResponse<String> reject(UUID application) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("targetStage", "REJECTED"); body.put("expectedCurrentStage", "NEW"); body.put("rejectionReasonId", reason);
        return call("POST", "/api/v1/applications/" + application + "/stage-changes", json.writeValueAsString(body), hrToken);
    }

    private void submit(String token) throws Exception {
        JsonNode form = form(token);
        var response = call("POST", ownPath() + "/submit",
                json.writeValueAsString(payload(form.path("version").asLong(), 4, 5, "HIRE")), token);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    }
}
