package vn.ttcs.recruitment.auth;

import jakarta.mail.Multipart;
import jakarta.mail.Part;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import vn.ttcs.recruitment.offerdelivery.OfferOutboxRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Only a loopback SMTP server with synthetic addresses; no external mail is sent. */
@TestPropertySource(properties="app.offers.delivery-enabled=true")
class OfferDeliveryIntegrationTest extends OfferDeliveryTestSupport {
    private static final LocalSmtpServer SMTP=smtp();
    @Autowired OfferOutboxRepository outboxRepository;
    @DynamicPropertySource static void mail(DynamicPropertyRegistry values) {
        values.add("spring.mail.host",() -> "127.0.0.1"); values.add("spring.mail.port",SMTP::port);
        values.add("spring.mail.properties.mail.smtp.auth",() -> false);
        values.add("spring.mail.properties.mail.smtp.starttls.enable",() -> false);
        values.add("spring.mail.properties.mail.smtp.starttls.required",() -> false);
        values.add("spring.mail.properties.mail.smtp.ssl.enable",() -> false);
    }
    @BeforeEach void resetMail() { SMTP.reset(); }
    @AfterAll static void closeMail() throws IOException { SMTP.close(); }
    private static LocalSmtpServer smtp() { try { return new LocalSmtpServer(); } catch(IOException e) { throw new IllegalStateException(e); } }

    @Test
    void fullHttpLifecycleDeliversMailWhoseActualLinkAcceptsTheApprovedOffer() throws Exception {
        jdbc.update("UPDATE job_applications SET stage='OFFER' WHERE id=?", first.applicationId());
        UUID position = jdbc.queryForObject("SELECT position_id FROM recruitment_requisitions WHERE id=?", UUID.class, requisition);
        jdbc.update("UPDATE positions SET salary_min=10000000,salary_max=30000000 WHERE id=?", position);

        var draft = Map.of(
                "salary", 20_000_000,
                "allowance", 2_000_000,
                "startDate", LocalDate.ofInstant(NOW, ZoneId.of("Asia/Ho_Chi_Minh")).plusDays(10).toString(),
                "responseDeadline", DEADLINE.toString());
        var created = call("POST", "/api/v1/applications/" + first.applicationId() + "/offers",
                json.writeValueAsString(draft), recruiterToken);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        var offer = json.readTree(created.body());
        UUID id = UUID.fromString(offer.path("id").asText());
        String path = "/api/v1/offers/" + id;
        assertThat(offer.path("status").asText()).isEqualTo("DRAFT");
        assertThat(offer.path("version").asLong()).isZero();

        var submitted = ok(call("POST", path + "/submit",
                json.writeValueAsString(Map.of("expectedVersion", 0, "hrApproverId", hr)), recruiterToken));
        assertThat(submitted.path("status").asText()).isEqualTo("PENDING_HR");
        assertThat(submitted.path("version").asLong()).isEqualTo(1);
        var approved = ok(call("POST", path + "/approval",
                json.writeValueAsString(Map.of("expectedVersion", 1, "action", "APPROVE")), hrToken));
        assertThat(approved.path("status").asText()).isEqualTo("APPROVED");
        assertThat(approved.path("version").asLong()).isEqualTo(2);

        var sent = ok(send(id, 2, recruiterToken));
        assertThat(sent.path("status").asText()).isEqualTo("SENT");
        assertThat(sent.path("version").asLong()).isEqualTo(3);
        assertThat(sent.path("deliveryStatus").asText()).isEqualTo("PENDING");
        assertThat(SMTP.messages()).isEmpty();
        assertThat(worker.processNext()).isTrue();
        assertThat(SMTP.messages()).hasSize(1);
        var message = SMTP.messages().getFirst();
        assertThat(message.getAllRecipients()).hasSize(1);
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo("lan@example.test");
        String deliveredText = plain(message);
        assertThat(deliveredText).contains("20.000.000", "2.000.000");
        var tokenMatch = Pattern.compile("#token=([A-Za-z0-9_-]{43})(?![A-Za-z0-9_-])").matcher(deliveredText);
        assertThat(tokenMatch.find()).as("The actual SMTP message must contain a usable response capability").isTrue();
        String responseToken = tokenMatch.group(1);

        var details = ok(lookup(responseToken));
        assertThat(details.path("salary").asLong()).isEqualTo(20_000_000);
        assertThat(details.path("allowance").asLong()).isEqualTo(2_000_000);
        var accepted = ok(respond(responseToken, "ACCEPT", "Tôi đồng ý các điều kiện trong thư."));
        assertThat(accepted.path("status").asText()).isEqualTo("ACCEPTED");
        assertThat(stage()).isEqualTo("HIRED");
        var finalOffer = ok(call("GET", path, null, recruiterToken));
        assertThat(finalOffer.path("status").asText()).isEqualTo("ACCEPTED");
        assertThat(finalOffer.path("version").asLong()).isEqualTo(4);
        assertThat(finalOffer.path("deliveryStatus").asText()).isEqualTo("SENT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_stage_events WHERE application_id=? AND offer_id=? AND actor_type='CANDIDATE' AND stage_before='OFFER' AND stage_after='HIRED'",
                Long.class, first.applicationId(), id)).isEqualTo(1);
        assertThat(count("application_stage_events")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM offer_events WHERE offer_id=?", Long.class, id)).isEqualTo(5);
    }

    @Test void sendsFrozenTermsAndPrivateReplyLinkThenErasesEncryptedPayload() throws Exception {
        var sent=queued(); UUID outbox=outbox(sent.id());
        assertThat(SMTP.messages()).isEmpty(); assertThat(worker.processNext()).isTrue();
        assertThat(SMTP.messages()).hasSize(1);
        var message=SMTP.messages().getFirst();
        assertThat(message.getAllRecipients()).hasSize(1);
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo("lan@example.test");
        assertThat(message.getMessageID()).isEqualTo(settings.messageId(outbox));
        assertThat(plain(message)).contains(sent.payload().positionTitle(),sent.payload().replyUrl()).doesNotContain("bandMin","explanation","approval");
        assertThat(delivery(sent.id())).isEqualTo("SENT");
        assertThat(state(sent.id())).isEqualTo("SENT"); assertThat(stage()).isEqualTo("OFFER");
        assertThat(jdbc.queryForObject("SELECT ciphertext IS NULL AND nonce IS NULL AND key_id IS NULL FROM offer_delivery_outbox WHERE offer_id=?",Boolean.class,sent.id())).isTrue();
        assertThat(worker.processNext()).isFalse();
    }
    @Test void transientSmtpFailureRetriesWithSameMessageIdentityAndBoundedAttemptCount() throws Exception {
        var sent=queued(); SMTP.rejectDelivery(true);
        assertThat(worker.processNext()).isTrue(); assertThat(delivery(sent.id())).isEqualTo("RETRY_WAIT");
        assertThat(SMTP.messages()).isEmpty(); SMTP.rejectDelivery(false); due(sent.id());
        assertThat(worker.processNext()).isTrue(); assertThat(delivery(sent.id())).isEqualTo("SENT");
        assertThat(SMTP.messages()).hasSize(1);
        assertThat(SMTP.messages().getFirst().getMessageID()).isEqualTo(settings.messageId(outbox(sent.id())));
        assertThat(jdbc.queryForObject("SELECT attempts FROM offer_delivery_outbox WHERE offer_id=?",Integer.class,sent.id())).isEqualTo(2);
    }
    @Test void sixTemporaryFailuresBecomeTerminalWithoutRetainingPrivatePayload() throws Exception {
        var sent=queued(); SMTP.rejectDelivery(true);
        for(int i=0;i<6;i++) { due(sent.id()); assertThat(worker.processNext()).isTrue(); }
        assertThat(delivery(sent.id())).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT last_failure FROM offer_delivery_outbox WHERE offer_id=?",String.class,sent.id())).isEqualTo("RETRIES_EXHAUSTED");
        assertThat(jdbc.queryForObject("SELECT ciphertext IS NULL FROM offer_delivery_outbox WHERE offer_id=?",Boolean.class,sent.id())).isTrue();
        assertThat(worker.processNext()).isFalse(); assertThat(SMTP.messages()).isEmpty();
    }
    @Test void refusedRecipientIsPermanentAndDiagnosticContainsOnlyFailureCode() throws Exception {
        var sent=queued(); SMTP.rejectRecipient("lan@example.test"); assertThat(worker.processNext()).isTrue();
        assertThat(delivery(sent.id())).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT last_failure FROM offer_delivery_outbox WHERE offer_id=?",String.class,sent.id())).isEqualTo("RECIPIENT_REFUSED");
        assertThat(SMTP.messages()).isEmpty();
    }
    @Test void changedRecipientIsDiscardedBeforeSmtpEvenBeforeMaintenanceRuns() throws Exception {
        var sent=queued(); jdbc.update("UPDATE job_applications SET email='another@example.test' WHERE id=?",first.applicationId());
        assertThat(worker.processNext()).isTrue(); assertThat(delivery(sent.id())).isEqualTo("CANCELLED");
        assertThat(SMTP.messages()).isEmpty();
    }
    @Test void withdrawnApplicationIsNotMailedEvenBeforeMaintenanceRuns() throws Exception {
        var sent=queued(); jdbc.update("UPDATE job_applications SET stage='WITHDRAWN' WHERE id=?",first.applicationId());
        assertThat(worker.processNext()).isTrue(); assertThat(delivery(sent.id())).isEqualTo("CANCELLED");
        assertThat(SMTP.messages()).isEmpty();
    }
    @Test void deadlineReachedBeforeDeliveryDiscardsMail() throws Exception {
        var sent=queued(); clock.set(DEADLINE);
        assertThat(worker.processNext()).isTrue(); assertThat(delivery(sent.id())).isEqualTo("CANCELLED");
        assertThat(SMTP.messages()).isEmpty();
    }
    @Test void brokenCiphertextIsTerminalAndNeverReachesSmtp() throws Exception {
        var sent=queued(); jdbc.update("UPDATE offer_delivery_outbox SET ciphertext=? WHERE offer_id=?",new byte[20],sent.id());
        assertThat(worker.processNext()).isTrue(); assertThat(delivery(sent.id())).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT last_failure FROM offer_delivery_outbox WHERE offer_id=?",String.class,sent.id())).isEqualTo("PAYLOAD_INVALID");
        assertThat(SMTP.messages()).isEmpty();
    }
    @Test void queueFailureRollsBackSentStateCapabilityAndAudit() throws Exception {
        UUID id=approved(); jdbc.execute("ALTER TABLE offer_delivery_outbox ADD CONSTRAINT test_block_offer_queue CHECK (false)");
        try {
            assertThat(send(id,0,hrToken).statusCode()).isEqualTo(500);
            assertThat(state(id)).isEqualTo("APPROVED"); assertThat(count("offer_response_tokens")).isZero();
            assertThat(count("offer_events")).isZero(); assertThat(count("offer_delivery_outbox")).isZero();
            assertThat(SMTP.messages()).isEmpty();
        } finally { jdbc.execute("ALTER TABLE offer_delivery_outbox DROP CONSTRAINT test_block_offer_queue"); }
    }
    @Test void reclaimedDatabaseLeaseFencesTheOldWorkerFromRenewingOrFinalizing() throws Exception {
        var sent=queued();
        Optional<?> firstClaim=ReflectionTestUtils.invokeMethod(outboxRepository,"claim");
        assertThat(firstClaim).isPresent(); Object old=firstClaim.orElseThrow();
        UUID firstLease=jdbc.queryForObject("SELECT lease_token FROM offer_delivery_outbox WHERE offer_id=?",UUID.class,sent.id());
        jdbc.update("UPDATE offer_delivery_outbox SET lease_until=clock_timestamp()-INTERVAL '1 second' WHERE offer_id=?",sent.id());
        assertThat(Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(outboxRepository,"sent",old))).isFalse();
        Optional<?> secondClaim=ReflectionTestUtils.invokeMethod(outboxRepository,"claim");
        assertThat(secondClaim).isPresent(); Object current=secondClaim.orElseThrow();
        assertThat(jdbc.queryForObject("SELECT lease_token FROM offer_delivery_outbox WHERE offer_id=?",UUID.class,sent.id())).isNotEqualTo(firstLease);
        assertThat(Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(outboxRepository,"renew",old))).isFalse();
        assertThat(Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(outboxRepository,"sent",old))).isFalse();
        assertThat(Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(outboxRepository,"failed",old,false,"RECIPIENT_REFUSED"))).isFalse();
        assertThat(delivery(sent.id())).isEqualTo("PROCESSING");
        assertThat(Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(outboxRepository,"sent",current))).isTrue();
        assertThat(delivery(sent.id())).isEqualTo("SENT"); assertThat(SMTP.messages()).isEmpty();
    }
    @Test void renewalDatabaseFenceRejectsParentClosureAndRecipientChange() throws Exception {
        var sent=queued(); Optional<?> claim=ReflectionTestUtils.invokeMethod(outboxRepository,"claim");
        Object delivery=claim.orElseThrow();
        jdbc.update("UPDATE recruitment_requisitions SET status='PAUSED' WHERE id=?",requisition);
        assertThat(Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(outboxRepository,"renew",delivery))).isFalse();
        jdbc.update("UPDATE recruitment_requisitions SET status='APPROVED' WHERE id=?",requisition);
        jdbc.update("UPDATE job_applications SET email='changed@example.test' WHERE id=?",first.applicationId());
        assertThat(Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(outboxRepository,"renew",delivery))).isFalse();
        assertThat(SMTP.messages()).isEmpty();
    }
    private UUID outbox(UUID offer) { return jdbc.queryForObject("SELECT id FROM offer_delivery_outbox WHERE offer_id=?",UUID.class,offer); }
    private static String plain(Part part) throws Exception {
        if(part.isMimeType("text/plain")) return part.getContent().toString();
        if(part.getContent() instanceof Multipart multipart) {
            var result=new StringBuilder();
            for(int i=0;i<multipart.getCount();i++) result.append(plain(multipart.getBodyPart(i)));
            return result.toString();
        }
        return "";
    }
}
