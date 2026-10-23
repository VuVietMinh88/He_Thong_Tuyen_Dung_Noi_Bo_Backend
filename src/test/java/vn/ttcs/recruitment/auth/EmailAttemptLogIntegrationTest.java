package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.application.ApplicationConfirmationWorker;
import vn.ttcs.recruitment.application.ApplicationReceipt;
import vn.ttcs.recruitment.application.ApplicationSubmission;
import vn.ttcs.recruitment.application.PostgresApplicationReceiptWriter;
import vn.ttcs.recruitment.invitation.InterviewInvitationWorker;
import vn.ttcs.recruitment.messaging.EmailAttemptRepository;
import vn.ttcs.recruitment.messaging.EmailAttemptRepository.Attempt;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/** Task 537: every delivery attempt of a candidate email is logged once, immutably, with a safe error code. */
@TestPropertySource(properties = {
        "app.interview-invitation.enabled=true", "app.interview-invitation.delivery-enabled=true",
        "app.interview-invitation.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.interview-invitation.page-url=http://localhost:8080/api/v1/public/interview-invitations/reply",
        "app.interview-invitation.poll-delay-ms=3600000"
})
class EmailAttemptLogIntegrationTest extends ScreeningTestSupport {
    private static final LocalSmtpServer SMTP = smtpServer();
    @Autowired PostgresApplicationReceiptWriter receipts;
    @Autowired ApplicationConfirmationWorker confirmations;
    @Autowired InterviewInvitationWorker invitations;
    @Autowired EmailAttemptRepository attempts;
    EmailTemplateTestData seeded;

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

    @BeforeEach void reset() { SMTP.reset(); seeded = new EmailTemplateTestData(jdbc); }
    @AfterEach void restore() { seeded.restore(); }

    @Test
    void temporaryFailureThenSuccessLogsTwoAttemptsWithTemplateRecipientAndSafeCode() throws Exception {
        jdbc.update("UPDATE email_templates SET version = 7 WHERE template_type = 'APPLICATION_RECEIPT'");
        ApplicationReceipt receipt = receipt("thu@example.test");
        UUID message = message(receipt.applicationId());
        SMTP.rejectDelivery(true);
        assertThat(confirmations.processNext()).isTrue();
        assertThat(status(message)).isEqualTo("RETRY_WAIT");
        SMTP.rejectDelivery(false);
        jdbc.update("UPDATE application_email_outbox SET next_attempt_at = clock_timestamp() - INTERVAL '1 second'");
        assertThat(confirmations.processNext()).isTrue();

        List<Attempt> log = attempts.forMessage(message);
        assertThat(log).extracting(Attempt::number, Attempt::result, Attempt::errorCode, Attempt::templateType,
                        Attempt::templateVersion, Attempt::recipientEmail, Attempt::delivery)
                .containsExactly(
                        tuple(1, "RETRY_SCHEDULED", "SMTP_TEMPORARY", "APPLICATION_RECEIPT", 7L, "thu@example.test",
                                "APPLICATION_CONFIRMATION"),
                        tuple(2, "SENT", null, "APPLICATION_RECEIPT", 7L, "thu@example.test", "APPLICATION_CONFIRMATION"));
        assertThat(log.get(0).attemptedAt()).isBeforeOrEqualTo(log.get(1).attemptedAt());
        assertThat(status(message)).isEqualTo("SENT");
        // The log holds codes only: never the server's reply, the tracking code or the body.
        assertThat(jdbc.queryForList("SELECT * FROM email_attempts").toString()).doesNotContain(receipt.trackingCode(),
                "Temporary test failure");
    }

    @Test
    void refusedMailboxAndExpiredLeaseAreLoggedAsTheirOwnAttempts() throws Exception {
        ApplicationReceipt refused = receipt("refused@example.test");
        SMTP.rejectRecipient("refused@example.test");
        assertThat(confirmations.processNext()).isTrue();
        assertThat(attempts.forMessage(message(refused.applicationId()))).extracting(Attempt::result, Attempt::errorCode)
                .containsExactly(tuple("FAILED", "RECIPIENT_REFUSED"));
        assertThat(status(message(refused.applicationId()))).isEqualTo("FAILED");

        SMTP.rejectRecipient(null);
        ApplicationReceipt crashed = receipt("crash@example.test");
        // A worker claimed the row and stopped before finishing: its lease has expired.
        jdbc.update("""
                UPDATE application_email_outbox SET status='PROCESSING', attempts=1, lease_token=?,
                    lease_until=clock_timestamp() - INTERVAL '1 second' WHERE application_id=?
                """, UUID.randomUUID(), crashed.applicationId());
        assertThat(confirmations.processNext()).isTrue();
        assertThat(attempts.forMessage(message(crashed.applicationId()))).extracting(Attempt::number, Attempt::result,
                Attempt::errorCode).containsExactly(tuple(1, "LEASE_EXPIRED", "DELIVERY_UNCERTAIN"), tuple(2, "SENT", null));
    }

    @Test
    void obsoleteInvitationIsLoggedAsCancelledWhileStaffMailsAndPendingCancellationsLogNothing() throws Exception {
        UUID panel = applications.account("Panel", "panel@example.test", Role.INTERVIEWER);
        var body = new LinkedHashMap<String, Object>();
        body.put("roundName", "Technical"); body.put("startsAt", ApplicationTestSupport.NOW.plusSeconds(86400).toString());
        body.put("durationMinutes", 60); body.put("mode", "ONSITE"); body.put("room", "Room A");
        body.put("participantIds", List.of(panel));
        var created = call("POST", "/api/v1/applications/" + first.applicationId() + "/interviews",
                json.writeValueAsString(body), hrToken);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        UUID message = message(first.applicationId());
        jdbc.update("UPDATE recruitment_requisitions SET status='PAUSED' WHERE id=?", requisition);
        for (int index = 0; index < 5 && invitations.processNext(); index++) { /* drain */ }
        assertThat(attempts.forMessage(message)).extracting(Attempt::result, Attempt::errorCode, Attempt::delivery)
                .containsExactly(tuple("CANCELLED", "OBSOLETE", "INTERVIEW_INVITATION"));
        assertThat(status(message)).isEqualTo("CANCELLED");
        // The panel member's email has no message, so it has no attempt either.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_attempts", Long.class)).isEqualTo(1);
        assertThat(SMTP.messages()).isEmpty();
    }

    @Test
    void attemptsAreNeverChangedOrDeletedAndKeepTheirMessage() throws Exception {
        ApplicationReceipt receipt = receipt("keep@example.test");
        assertThat(confirmations.processNext()).isTrue();
        assertThatThrownBy(() -> jdbc.update("UPDATE email_attempts SET result='FAILED', error_code='X'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO email_attempts(id,message_id,attempt_number,delivery,delivery_id,template_type,recipient_email,
                    result,error_code,attempted_at)
                SELECT gen_random_uuid(),message_id,99,delivery,delivery_id,template_type,recipient_email,'SENT','X',now()
                FROM email_attempts LIMIT 1
                """)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM email_attempts"))
                .isInstanceOf(DataIntegrityViolationException.class);
        // Neither the message nor its application can take the log away (RESTRICT, like the other append-only logs).
        assertThatThrownBy(() -> jdbc.update("DELETE FROM email_messages WHERE application_id=?", receipt.applicationId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("DELETE FROM application_email_outbox WHERE application_id=?", receipt.applicationId());
        jdbc.update("DELETE FROM candidate_applications WHERE application_id=?", receipt.applicationId());
        jdbc.update("DELETE FROM application_cvs WHERE application_id=?", receipt.applicationId());
        assertThatThrownBy(() -> jdbc.update("DELETE FROM job_applications WHERE id=?", receipt.applicationId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_attempts", Long.class)).isEqualTo(1);
        // Disposable test databases reset it with TRUNCATE, which fires no row trigger.
        jdbc.execute("TRUNCATE email_messages CASCADE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_attempts", Long.class)).isZero();
    }

    @Test
    void theFixedTextInterviewUpdateRecordsNoTemplateVersion() throws Exception {
        jdbc.update("UPDATE email_templates SET version = 4 WHERE template_type = 'INTERVIEW_INVITATION'");
        UUID panel = applications.account("Panel", "panel@example.test", Role.INTERVIEWER);
        var body = new LinkedHashMap<String, Object>();
        body.put("roundName", "Technical"); body.put("startsAt", ApplicationTestSupport.NOW.plusSeconds(86400).toString());
        body.put("durationMinutes", 60); body.put("mode", "ONSITE"); body.put("room", "Room A");
        body.put("participantIds", List.of(panel));
        var created = call("POST", "/api/v1/applications/" + first.applicationId() + "/interviews",
                json.writeValueAsString(body), hrToken);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        UUID interview = UUID.fromString(json.readTree(created.body()).path("id").asText());
        for (int index = 0; index < 5 && invitations.processNext(); index++) { /* drain */ }
        body.put("startsAt", ApplicationTestSupport.NOW.plusSeconds(2 * 86400).toString());
        body.put("expectedVersion", 0);
        ok(call("PUT", "/api/v1/interviews/" + interview, json.writeValueAsString(body), hrToken));
        for (int index = 0; index < 5 && invitations.processNext(); index++) { /* drain */ }
        assertThat(jdbc.queryForList("""
                SELECT m.variant, t.result, t.template_version FROM email_attempts t
                JOIN email_messages m ON m.id = t.message_id ORDER BY m.created_at
                """)).extracting(row -> row.get("variant"), row -> row.get("result"), row -> row.get("template_version"))
                .containsExactly(tuple("INVITATION", "SENT", 4L), tuple("UPDATED", "SENT", null));
    }

    private ApplicationReceipt receipt(String email) {
        return receipts.accept(applications.publishedPosting("Log " + email),
                new ApplicationSubmission("Người Thử", email, ApplicationTestSupport.PHONE, null, null), applications.pdf());
    }

    private UUID message(UUID application) {
        return jdbc.queryForObject("SELECT id FROM email_messages WHERE application_id=? ORDER BY created_at LIMIT 1",
                UUID.class, application);
    }

    private String status(UUID message) {
        return jdbc.queryForObject("SELECT status FROM email_messages WHERE id=?", String.class, message);
    }
}
