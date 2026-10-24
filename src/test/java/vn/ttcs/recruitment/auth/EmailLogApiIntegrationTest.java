package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import vn.ttcs.recruitment.application.ApplicationConfirmationWorker;
import vn.ttcs.recruitment.application.ApplicationReceipt;
import vn.ttcs.recruitment.application.ApplicationSubmission;
import vn.ttcs.recruitment.application.PostgresApplicationReceiptWriter;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.invitation.InterviewInvitationCrypto;
import vn.ttcs.recruitment.invitation.InterviewInvitationWorker;
import vn.ttcs.recruitment.messaging.CandidateMailWorker;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** Task 539: the scoped email log and the retry of failed emails, keeping every earlier attempt. */
@TestPropertySource(properties = {"app.offers.delivery-enabled=true", "app.candidate-mail.poll-delay-ms=3600000",
        "app.interview-invitation.enabled=true", "app.interview-invitation.delivery-enabled=true",
        "app.interview-invitation.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.interview-invitation.page-url=http://localhost:8080/api/v1/public/interview-invitations/reply",
        "app.interview-invitation.poll-delay-ms=3600000"})
class EmailLogApiIntegrationTest extends OfferDeliveryTestSupport {
    private static final LocalSmtpServer SMTP = smtpServer();
    private static final String EMAILS = "/api/v1/messaging/emails";
    @Autowired PostgresApplicationReceiptWriter receipts;
    @Autowired ApplicationConfirmationWorker confirmations;
    @Autowired CandidateMailWorker candidateMail;
    @Autowired InterviewInvitationWorker invitations;
    @Autowired InterviewInvitationCrypto invitationCrypto;
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

    @BeforeEach void reset() {
        SMTP.reset();
        seeded = new EmailTemplateTestData(jdbc);
        reason = catalog("REJECTION_REASON", "Chưa phù hợp", true);
        jdbc.execute("TRUNCATE candidate_access_events");
    }
    @AfterEach void restore() { seeded.restore(); }

    @Test
    void logListsOnlyEmailsOfReadableApplicationsWithTemplateTimesAndResults() throws Exception {
        UUID mine = failedRejection(first.applicationId());
        UUID others = failedRejection(other.applicationId());
        JsonNode page = ok(call("GET", EMAILS + "?type=REJECTION&status=FAILED", null, recruiterToken));
        assertThat(page.path("totalItems").asLong()).isEqualTo(1);
        JsonNode item = page.path("items").path(0);
        assertThat(item.path("id").asText()).isEqualTo(mine.toString());
        assertThat(item.path("typeName").asText()).isEqualTo("Thư từ chối");
        assertThat(item.path("status").asText()).isEqualTo("FAILED");
        assertThat(item.path("lastFailure").asText()).isEqualTo("RECIPIENT_REFUSED");
        assertThat(item.path("attemptCount").asInt()).isEqualTo(1);
        assertThat(item.path("retryable").asBoolean()).isTrue();
        assertThat(item.path("createdAt").asText()).isNotBlank();
        assertThat(ok(call("GET", EMAILS, null, hrToken)).path("totalItems").asLong()).isEqualTo(2);
        // RECRUITER B reads only its own requisition: the first application's email is 403, logged as refused.
        assertThat(call("GET", EMAILS + "/" + mine, null, token(outsider)).statusCode()).isEqualTo(403);
        assertThat(call("GET", EMAILS + "/" + others, null, token(outsider)).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForList("SELECT outcome FROM candidate_access_events WHERE application_id=?", String.class,
                first.applicationId())).contains("GRANTED", "DENIED");
        assertThat(call("GET", EMAILS + "/" + UUID.randomUUID(), null, hrToken).statusCode()).isEqualTo(404);
        var bad = call("GET", EMAILS + "?size=101&type=NOPE", null, hrToken);
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(json.readTree(bad.body()).path("fieldErrors").has("size")).isTrue();
    }

    @Test
    void retryQueuesOnlyAFailedEmailAgainAndKeepsItsEarlierAttempts() throws Exception {
        UUID message = failedRejection(first.applicationId());
        SMTP.rejectRecipient(null);
        JsonNode retried = json.readTree(accepted(call("POST", EMAILS + "/" + message + "/retry", null, recruiterToken)).body());
        assertThat(retried.path("status").asText()).isEqualTo("QUEUED");
        assertThat(retried.path("retryCount").asInt()).isEqualTo(1);
        assertThat(retried.path("lastRetriedByName").asText()).isEqualTo("Recruiter A");
        var again = call("POST", EMAILS + "/" + message + "/retry", null, recruiterToken);
        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(json.readTree(again.body()).path("code").asText()).isEqualTo("EMAIL_RETRY_NOT_FAILED");
        assertThat(candidateMail.processNext()).isTrue();
        JsonNode detail = ok(call("GET", EMAILS + "/" + message, null, hrToken));
        assertThat(detail.path("message").path("status").asText()).isEqualTo("SENT");
        List<String> results = new ArrayList<>();
        detail.path("attempts").forEach(attempt -> results.add(attempt.path("number").asInt() + ":"
                + attempt.path("result").asText() + ":" + (attempt.path("errorCode").isNull() ? "-" : attempt.path("errorCode").asText())));
        assertThat(results).containsExactly("1:FAILED:RECIPIENT_REFUSED", "2:SENT:-");
        assertThat(call("POST", EMAILS + "/" + message + "/retry", null, recruiterToken).statusCode()).isEqualTo(409);
    }

    @Test
    void aFailedRejectionLetterIsNotRetriedWhileAnotherLetterIsOnItsWay() throws Exception {
        UUID failed = failedRejection(first.applicationId());
        // After the application was reopened and rejected again, a newer rejection letter is queued.
        jdbc.update("""
                INSERT INTO email_messages(id, template_type, application_id, trigger_kind, status, delivery, delivery_id,
                    recipient_email, dedupe_key, created_at, updated_at)
                VALUES (gen_random_uuid(), 'REJECTION', ?, 'MANUAL', 'QUEUED', 'CANDIDATE_MAIL', gen_random_uuid(),
                    'lan@example.test', 'REJECTION:MANUAL:' || ?, now(), now())
                """, first.applicationId(), first.applicationId());
        SMTP.rejectRecipient(null);
        var duplicate = call("POST", EMAILS + "/" + failed + "/retry", null, hrToken);
        assertThat(duplicate.statusCode()).as(duplicate.body()).isEqualTo(409);
        assertThat(json.readTree(duplicate.body()).path("code").asText()).isEqualTo("EMAIL_RETRY_OBSOLETE");
        assertThat(jdbc.queryForObject("SELECT status FROM email_messages WHERE id=?", String.class, failed)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT status FROM candidate_mail_outbox WHERE message_id=?", String.class, failed))
                .isEqualTo("FAILED");
    }

    @Test
    void concurrentRetriesHaveExactlyOneWinner() throws Exception {
        UUID message = failedRejection(first.applicationId());
        String hr = hrToken, recruiterAgain = token(recruiter);
        List<Callable<Integer>> calls = List.of(
                () -> call("POST", EMAILS + "/" + message + "/retry", null, hr).statusCode(),
                () -> call("POST", EMAILS + "/" + message + "/retry", null, recruiterAgain).statusCode());
        List<Integer> statuses = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (Future<Integer> result : executor.invokeAll(calls)) statuses.add(result.get());
        }
        assertThat(statuses).containsExactlyInAnyOrder(202, 409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_mail_outbox WHERE status='PENDING'", Long.class))
                .isEqualTo(1);
    }

    @Test
    void retryChecksScopeAndCurrentStateAndNeverRevivesARevokedCapability() throws Exception {
        UUID message = failedRejection(first.applicationId());
        assertThat(call("POST", EMAILS + "/" + message + "/retry", null, token(outsider)).statusCode()).isEqualTo(403);
        assertThat(call("POST", EMAILS + "/" + message + "/retry", null, token(manager)).statusCode()).isEqualTo(403);
        jdbc.update("UPDATE job_applications SET email='moved@example.test', normalized_email='moved@example.test' WHERE id=?",
                first.applicationId());
        var moved = call("POST", EMAILS + "/" + message + "/retry", null, hrToken);
        assertThat(moved.statusCode()).isEqualTo(409);
        assertThat(json.readTree(moved.body()).path("code").asText()).isEqualTo("EMAIL_RETRY_OBSOLETE");
        assertThat(jdbc.queryForObject("SELECT status FROM email_messages WHERE id=?", String.class, message)).isEqualTo("FAILED");

        // A failed offer email: the retry rotates the token, so the link of the failed email stops working.
        jdbc.update("UPDATE job_applications SET email='lan@example.test', normalized_email='lan@example.test' WHERE id=?",
                first.applicationId());
        Sent sent = queued();
        SMTP.rejectRecipient("lan@example.test");
        due(sent.id());
        assertThat(worker.processNext()).isTrue();
        UUID offerMessage = jdbc.queryForObject("SELECT id FROM email_messages WHERE offer_id=?", UUID.class, sent.id());
        assertThat(jdbc.queryForObject("SELECT status FROM email_messages WHERE id=?", String.class, offerMessage)).isEqualTo("FAILED");
        SMTP.rejectRecipient(null);
        accepted(call("POST", EMAILS + "/" + offerMessage + "/retry", null, hrToken));
        error(lookup(sent.token()), 400, "OFFER_LINK_INVALID");
        String fresh = payload(sent.id()).replyUrl();
        assertThat(fresh).doesNotContain(sent.token());
        due(sent.id());
        assertThat(worker.processNext()).isTrue();
        assertThat(CandidateEmailTemplateDeliveryIntegrationTest.plain(SMTP.messages().getLast())).contains(fresh);

        // Once the offer token is revoked (the offer was cancelled), a failed email can never be retried.
        UUID secondMessage = offerMessage;
        jdbc.update("UPDATE offer_delivery_outbox SET status='FAILED', last_failure='RECIPIENT_REFUSED', sent_at=NULL,"
                + " key_id=NULL, nonce=NULL, ciphertext=NULL WHERE offer_id=?", sent.id());
        jdbc.update("UPDATE offer_response_tokens SET revoked_at=now() WHERE offer_id=?", sent.id());
        var revoked = call("POST", EMAILS + "/" + secondMessage + "/retry", null, hrToken);
        assertThat(revoked.statusCode()).isEqualTo(409);
        assertThat(json.readTree(revoked.body()).path("code").asText()).isEqualTo("EMAIL_RETRY_OBSOLETE");
    }

    @Test
    void failedReceiptIsRetriedFromItsKeptPayloadUnlessTheTrackingCredentialWasRevoked() throws Exception {
        ApplicationReceipt receipt = receipts.accept(applications.publishedPosting("Gửi lại"),
                new ApplicationSubmission("Mai Lan", "retry@example.test", ApplicationTestSupport.PHONE, null, null),
                applications.pdf());
        UUID message = jdbc.queryForObject("SELECT id FROM email_messages WHERE application_id=?", UUID.class,
                receipt.applicationId());
        SMTP.rejectRecipient("retry@example.test");
        assertThat(confirmations.processNext()).isTrue();
        SMTP.rejectRecipient(null);
        // ADMIN holds every permission; HR reads the application through CANDIDATES_READ_ALL as well.
        accepted(call("POST", EMAILS + "/" + message + "/retry", null, hrToken));
        assertThat(confirmations.processNext()).isTrue();
        assertThat(String.valueOf(SMTP.messages().getLast().getContent())).contains(receipt.trackingCode());

        jdbc.update("UPDATE application_email_outbox SET status='FAILED', sent_at=NULL, last_failure='RECIPIENT_REFUSED',"
                + " key_id='v1', nonce=decode(repeat('00',12),'hex'), ciphertext=decode(repeat('00',16),'hex')"
                + " WHERE application_id=?", receipt.applicationId());
        jdbc.update("UPDATE job_applications SET tracking_revoked_at=now() WHERE id=?", receipt.applicationId());
        var revoked = call("POST", EMAILS + "/" + message + "/retry", null, hrToken);
        assertThat(revoked.statusCode()).isEqualTo(409);
        assertThat(json.readTree(revoked.body()).path("code").asText()).isEqualTo("EMAIL_RETRY_OBSOLETE");
    }

    @Test
    void failedInvitationIsRebuiltWithANewCapabilityAndAMovedInterviewIsNotRetried() throws Exception {
        UUID panel = applications.account("Panel", "panel@example.test", Role.INTERVIEWER);
        var body = new LinkedHashMap<String, Object>();
        body.put("roundName", "Technical"); body.put("startsAt", ApplicationTestSupport.NOW.plusSeconds(86400).toString());
        body.put("durationMinutes", 60); body.put("mode", "ONSITE"); body.put("room", "Room A");
        body.put("participantIds", List.of(panel));
        var created = call("POST", "/api/v1/applications/" + first.applicationId() + "/interviews",
                json.writeValueAsString(body), hrToken);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        UUID interview = UUID.fromString(json.readTree(created.body()).path("id").asText());
        UUID failedRow = jdbc.queryForObject("SELECT id FROM interview_invitation_outbox WHERE recipient_user_id IS NULL",
                UUID.class);
        String oldLink = candidateLink(failedRow, interview);
        UUID oldInvitation = jdbc.queryForObject("SELECT id FROM interview_invitations WHERE revoked_at IS NULL", UUID.class);
        SMTP.rejectRecipient("lan@example.test");
        for (int i = 0; i < 5 && invitations.processNext(); i++) { /* drain */ }
        UUID message = jdbc.queryForObject("SELECT id FROM email_messages WHERE template_type='INTERVIEW_INVITATION'", UUID.class);
        assertThat(jdbc.queryForObject("SELECT status FROM email_messages WHERE id=?", String.class, message)).isEqualTo("FAILED");

        SMTP.rejectRecipient(null);
        SMTP.reset();
        accepted(call("POST", EMAILS + "/" + message + "/retry", null, hrToken));
        UUID newRow = jdbc.queryForObject("SELECT delivery_id FROM email_messages WHERE id=?", UUID.class, message);
        assertThat(newRow).isNotEqualTo(failedRow);
        String newLink = candidateLink(newRow, interview);
        assertThat(newLink).isNotEqualTo(oldLink);
        assertThat(jdbc.queryForObject("SELECT revoked_at IS NOT NULL FROM interview_invitations WHERE id=?", Boolean.class,
                oldInvitation)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM interview_invitations WHERE revoked_at IS NULL", Long.class)).isEqualTo(1);
        // The failed row stays as it was (its history), and the staff email was not touched.
        assertThat(jdbc.queryForObject("SELECT status FROM interview_invitation_outbox WHERE id=?", String.class, failedRow))
                .isEqualTo("FAILED");
        for (int i = 0; i < 5 && invitations.processNext(); i++) { /* drain */ }
        assertThat(CandidateEmailTemplateDeliveryIntegrationTest.plain(SMTP.messages().getLast())).contains(newLink);
        assertThat(jdbc.queryForList("SELECT result FROM email_attempts WHERE message_id=? ORDER BY attempt_number",
                String.class, message)).containsExactly("FAILED", "SENT");

        // A failure of the rebuilt email after the interview moved: the newer schedule's email is the one to send.
        jdbc.update("UPDATE interview_invitation_outbox SET status='FAILED', last_failure='RECIPIENT_REFUSED', sent_at=NULL"
                + " WHERE id=?", newRow);
        var move = new LinkedHashMap<>(body);
        move.put("startsAt", ApplicationTestSupport.NOW.plusSeconds(2 * 86400).toString());
        move.put("expectedVersion", 0);
        ok(call("PUT", "/api/v1/interviews/" + interview, json.writeValueAsString(move), hrToken));
        var stale = call("POST", EMAILS + "/" + message + "/retry", null, hrToken);
        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(json.readTree(stale.body()).path("code").asText()).isEqualTo("EMAIL_RETRY_OBSOLETE");
    }

    private String candidateLink(UUID row, UUID interview) {
        return jdbc.queryForObject("SELECT key_id, nonce, ciphertext FROM interview_invitation_outbox WHERE id=?",
                (result, number) -> invitationCrypto.decrypt(row, interview, result.getString("key_id"),
                        result.getBytes("nonce"), result.getBytes("ciphertext")).replyUrl(), row);
    }

    private UUID failedRejection(UUID application) throws Exception {
        seeded.autoSend("REJECTION", true);
        var body = new LinkedHashMap<String, Object>();
        body.put("targetStage", "REJECTED"); body.put("expectedCurrentStage", "NEW"); body.put("rejectionReasonId", reason);
        ok(call("POST", "/api/v1/applications/" + application + "/stage-changes", json.writeValueAsString(body), hrToken));
        SMTP.rejectRecipient("lan@example.test");
        assertThat(candidateMail.processNext()).isTrue();
        return jdbc.queryForObject("SELECT id FROM email_messages WHERE application_id=? AND template_type='REJECTION'",
                UUID.class, application);
    }

    private HttpResponse<String> accepted(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        return response;
    }
}
