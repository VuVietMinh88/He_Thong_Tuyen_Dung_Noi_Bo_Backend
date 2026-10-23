package vn.ttcs.recruitment.auth;

import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.invitation.InterviewInvitationCrypto;
import vn.ttcs.recruitment.invitation.InterviewInvitationSettings;
import vn.ttcs.recruitment.invitation.InterviewInvitationWorker;
import vn.ttcs.recruitment.invitation.InterviewMailPayload;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Real loopback SMTP + disposable PostgreSQL; no remote mailbox, production data or external SMTP is used. */
@TestPropertySource(properties = {
        "app.interview-invitation.enabled=true", "app.interview-invitation.delivery-enabled=true",
        "app.interview-invitation.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.interview-invitation.page-url=http://localhost:8080/api/v1/public/interview-invitations/reply",
        "app.interview-invitation.poll-delay-ms=3600000"
})
class InterviewInvitationDeliveryIntegrationTest extends ScreeningTestSupport {
    private static final LocalSmtpServer SMTP = smtpServer();
    private static final Instant START = ApplicationTestSupport.NOW.plusSeconds(86400);
    @Autowired InterviewInvitationWorker worker;
    @Autowired InterviewInvitationCrypto crypto;
    @Autowired InterviewInvitationSettings invitationSettings;
    private UUID panel;

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
        try { return new LocalSmtpServer(); }
        catch (IOException failure) { throw new IllegalStateException(failure); }
    }
    @BeforeEach void invitationFixture() {
        SMTP.reset();
        panel = applications.account("Interview panel", "panel@example.test", Role.INTERVIEWER);
    }

    @Test void bulkMessagesContainEachRecipientsOwnTimeAndOnlyCandidatesHaveReplyLinks() throws Exception {
        jdbc.update("UPDATE job_applications SET email='second@example.test',normalized_email='second@example.test' WHERE id=?",
                other.applicationId());
        var body = new LinkedHashMap<String, Object>();
        body.put("requestId", UUID.randomUUID()); body.put("applicationIds", List.of(first.applicationId(), other.applicationId()));
        body.put("roundName", "Technical"); body.put("windowStart", START.toString());
        body.put("windowEnd", START.plusSeconds(7200).toString()); body.put("durationMinutes", 60);
        body.put("mode", "ONSITE"); body.put("room", "Room A"); body.put("participantIds", List.of(panel));
        body.put("preparationInstructions", "Mang CV và bài tập.");
        created(call("POST", "/api/v1/interviews/bulk", json.writeValueAsString(body), hrToken));
        var pending = payloads();
        assertThat(pending).hasSize(4);
        assertThat(pending.stream().filter(p -> p.email().equals("lan@example.test")).findFirst().orElseThrow().startsAt()).isEqualTo(START);
        assertThat(pending.stream().filter(p -> p.email().equals("second@example.test")).findFirst().orElseThrow().startsAt())
                .isEqualTo(START.plusSeconds(3600));
        assertThat(pending.stream().filter(p -> p.email().equals("panel@example.test"))).allSatisfy(p -> assertThat(p.replyUrl()).isNull());
        for (InterviewMailPayload payload : pending) {
            if (!payload.email().equals("panel@example.test")) {
                assertThat(payload.replyUrl()).contains("#token=");
                String token = payload.replyUrl().substring(payload.replyUrl().indexOf("#token=") + 7);
                assertThat(token).matches("[A-Za-z0-9_-]{43}");
                assertThat(jdbc.queryForList("SELECT token_hash FROM interview_invitations", String.class)).doesNotContain(token);
            }
        }
        drain();
        assertThat(SMTP.messages()).hasSize(4);
        for (MimeMessage mail : SMTP.messages()) {
            assertThat(mail.getAllRecipients()).hasSize(1);
            String text = plain(mail);
            assertThat(text).contains("Room A", "Mang CV và bài tập.");
            if (mail.getAllRecipients()[0].toString().equals("panel@example.test")) assertThat(text).doesNotContain("#token=");
            else assertThat(text).contains("#token=");
        }
        assertThat(plain(messageTo("lan@example.test"))).contains("10:00").doesNotContain("12:00");
        assertThat(plain(messageTo("second@example.test"))).contains("11:00", "12:00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM interview_invitation_outbox WHERE status='SENT' AND ciphertext IS NULL AND nonce IS NULL AND key_id IS NULL", Long.class)).isEqualTo(4);
    }

    @Test void failedQueueWriteRollsBackAppointmentEventInvitationAndOutboxTogether() throws Exception {
        jdbc.execute("ALTER TABLE interview_invitation_outbox ADD CONSTRAINT test_no_staff_mail CHECK (recipient_user_id IS NULL)");
        try {
            HttpResponse<String> response = call("POST", "/api/v1/applications/" + first.applicationId() + "/interviews",
                    json.writeValueAsString(schedule(START, panel)), hrToken);
            assertThat(response.statusCode()).isEqualTo(500);
            for (String table : List.of("interview_sessions", "interview_events", "interview_invitations", "interview_invitation_outbox")) {
                assertThat(count(table)).as(table).isZero();
            }
            assertThat(SMTP.messages()).isEmpty();
        } finally { jdbc.execute("ALTER TABLE interview_invitation_outbox DROP CONSTRAINT test_no_staff_mail"); }
    }

    @Test void temporarySmtpFailureRetriesWithoutLosingOrDuplicatingTheAcceptedMessage() throws Exception {
        single(); UUID id = onlyCandidateDue();
        SMTP.rejectDelivery(true);
        assertThat(worker.processNext()).isTrue();
        assertThat(state(id)).isEqualTo("RETRY_WAIT");
        assertThat(SMTP.messages()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT last_failure FROM interview_invitation_outbox WHERE id=?", String.class, id)).isEqualTo("SMTP_TEMPORARY");
        SMTP.rejectDelivery(false); due(id);
        assertThat(worker.processNext()).isTrue();
        assertThat(state(id)).isEqualTo("SENT");
        assertThat(SMTP.messages()).hasSize(1);
        assertThat(SMTP.messages().getFirst().getMessageID()).isEqualTo(invitationSettings.messageId(id));
        assertThat(jdbc.queryForObject("SELECT attempts FROM interview_invitation_outbox WHERE id=?", Integer.class, id)).isEqualTo(2);
        assertThat(worker.processNext()).isFalse();
    }

    @Test void sixTemporaryFailuresBecomeTerminalAndEraseThePayload() throws Exception {
        single(); UUID id = onlyCandidateDue(); SMTP.rejectDelivery(true);
        for (int attempt = 1; attempt <= 6; attempt++) { due(id); assertThat(worker.processNext()).isTrue(); }
        assertThat(state(id)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT last_failure FROM interview_invitation_outbox WHERE id=?", String.class, id)).isEqualTo("RETRIES_EXHAUSTED");
        assertThat(jdbc.queryForObject("SELECT ciphertext IS NULL AND nonce IS NULL AND key_id IS NULL FROM interview_invitation_outbox WHERE id=?", Boolean.class, id)).isTrue();
        assertThat(worker.processNext()).isFalse();
    }

    @Test void refusedMailboxIsPermanentWithoutRetainingCandidatePayload() throws Exception {
        single(); UUID id = onlyCandidateDue(); SMTP.rejectRecipient("lan@example.test");
        assertThat(worker.processNext()).isTrue();
        assertThat(state(id)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT last_failure FROM interview_invitation_outbox WHERE id=?", String.class, id)).isEqualTo("RECIPIENT_REFUSED");
        assertThat(jdbc.queryForObject("SELECT ciphertext IS NULL FROM interview_invitation_outbox WHERE id=?", Boolean.class, id)).isTrue();
        assertThat(SMTP.messages()).isEmpty();
    }

    @Test void cancellationRevokesTokenDiscardsOldMailAndSendsOnlyCancellation() throws Exception {
        UUID interview = single();
        String oldUrl = payloads().stream().filter(p -> p.email().equals("lan@example.test")).findFirst().orElseThrow().replyUrl();
        ok(call("POST", "/api/v1/interviews/" + interview + "/cancel",
                json.writeValueAsString(Map.of("expectedVersion", 0, "reason", "Lịch tuyển dụng thay đổi")), hrToken));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM interview_invitations WHERE revoked_at IS NULL", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM interview_invitation_outbox WHERE status='CANCELLED' AND ciphertext IS NULL", Long.class)).isEqualTo(2);
        drain(); assertThat(SMTP.messages()).hasSize(2);
        for (MimeMessage message : SMTP.messages()) {
            assertThat(plain(message)).contains("Lịch tuyển dụng thay đổi").doesNotContain(oldUrl, "#token=", "https://meeting.example.test");
        }
    }

    @Test void reschedulingAndReplacementPanelKeepRemovalNoticeAcrossAnotherEdit() throws Exception {
        UUID interview = single();
        UUID replacement = applications.account("Replacement", "replacement@example.test", Role.INTERVIEWER);
        update(interview, 0, START.plusSeconds(7200), replacement);
        update(interview, 1, START.plusSeconds(10800), replacement);
        assertThat(payloads().stream().filter(p -> "REMOVED".equals(p.kind()))).hasSize(1);
        drain(); assertThat(SMTP.messages()).hasSize(3);
        String removal = plain(messageTo("panel@example.test"));
        assertThat(removal).doesNotContain("#token=", "Room A", "https://meeting.example.test");
        assertThat(plain(messageTo("lan@example.test"))).contains("13:00", "#token=");
        assertThat(plain(messageTo("replacement@example.test"))).contains("13:00").doesNotContain("#token=");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM interview_invitations WHERE revoked_at IS NULL", Long.class)).isEqualTo(1);
    }

    @Test void withdrawingApplicationAndDisablingInterviewerPreventObsoleteMail() throws Exception {
        single();
        jdbc.update("UPDATE job_applications SET stage='WITHDRAWN' WHERE id=?", first.applicationId());
        jdbc.update("UPDATE user_accounts SET enabled=false WHERE id=?", panel);
        drain();
        assertThat(SMTP.messages()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM interview_invitation_outbox WHERE status='CANCELLED' AND ciphertext IS NULL", Long.class)).isEqualTo(2);
    }

    @Test void disabledPanelAddressIsSkippedWhileCandidateStillReceivesInvitation() throws Exception {
        single(); jdbc.update("UPDATE user_accounts SET enabled=false WHERE id=?", panel);
        drain();
        assertThat(SMTP.messages()).hasSize(1);
        assertThat(messageTo("lan@example.test")).isNotNull();
        assertThat(jdbc.queryForObject("SELECT last_failure FROM interview_invitation_outbox WHERE recipient_user_id=?", String.class, panel))
                .isEqualTo("RECIPIENT_UNAVAILABLE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"INTERVIEWS_READ_SCOPED", "EVALUATIONS_WRITE_SCOPED"})
    void revokedPanelGrantStopsQueuedMeetingDetails(String permission) throws Exception {
        single();
        assertThat(jdbc.update("DELETE FROM role_permissions WHERE role_code='INTERVIEWER' AND permission_code=?", permission)).isEqualTo(1);
        try {
            drain();
            assertThat(SMTP.messages()).hasSize(1);
            assertThat(messageTo("lan@example.test")).isNotNull();
            assertThat(jdbc.queryForObject("SELECT last_failure FROM interview_invitation_outbox WHERE recipient_user_id=?", String.class, panel))
                    .isEqualTo("RECIPIENT_UNAVAILABLE");
        } finally {
            jdbc.update("INSERT INTO role_permissions(role_code,permission_code) VALUES ('INTERVIEWER',?) ON CONFLICT DO NOTHING", permission);
        }
    }

    @Test void abandonedSixthLeaseFailsWithoutSendingOrRetainingPayload() throws Exception {
        single(); UUID id = onlyCandidateDue();
        jdbc.update("""
                UPDATE interview_invitation_outbox SET status='PROCESSING',attempts=6,lease_token=?,
                    lease_until=clock_timestamp()-INTERVAL '1 second' WHERE id=?
                """, UUID.randomUUID(), id);
        assertThat(worker.processNext()).isFalse();
        assertThat(state(id)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT last_failure FROM interview_invitation_outbox WHERE id=?", String.class, id))
                .isEqualTo("DELIVERY_UNCERTAIN");
        assertThat(jdbc.queryForObject("SELECT ciphertext IS NULL AND lease_token IS NULL FROM interview_invitation_outbox WHERE id=?", Boolean.class, id)).isTrue();
        assertThat(SMTP.messages()).isEmpty();
    }

    @Test void tamperedCiphertextCannotReachSmtp() throws Exception {
        single(); UUID id = onlyCandidateDue();
        byte[] encrypted = jdbc.queryForObject("SELECT ciphertext FROM interview_invitation_outbox WHERE id=?", byte[].class, id);
        encrypted[0] ^= 1;
        jdbc.update("UPDATE interview_invitation_outbox SET ciphertext=? WHERE id=?", encrypted, id);
        assertThat(worker.processNext()).isTrue();
        assertThat(state(id)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT last_failure FROM interview_invitation_outbox WHERE id=?", String.class, id)).isEqualTo("PAYLOAD_INVALID");
        assertThat(SMTP.messages()).isEmpty();
    }

    @Test void malformedDecryptedContentFailsPermanentlyBeforeSmtp() throws Exception {
        UUID interview = single(); UUID id = onlyCandidateDue();
        var original = payloads().stream().filter(payload -> payload.email().equals("lan@example.test")).findFirst().orElseThrow();
        var malformed = new InterviewMailPayload(original.email(), original.recipientName(), original.positionTitle(),
                original.roundName(), original.startsAt(), null, original.mode(), original.room(), original.meetingUrl(),
                original.preparationInstructions(), original.kind(), original.replyUrl(), original.reason());
        var encrypted = crypto.encrypt(id, interview, malformed);
        jdbc.update("UPDATE interview_invitation_outbox SET key_id=?,nonce=?,ciphertext=? WHERE id=?",
                encrypted.keyId(), encrypted.nonce(), encrypted.ciphertext(), id);
        assertThat(worker.processNext()).isTrue();
        assertThat(state(id)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT last_failure FROM interview_invitation_outbox WHERE id=?", String.class, id)).isEqualTo("PAYLOAD_INVALID");
        assertThat(jdbc.queryForObject("SELECT ciphertext IS NULL FROM interview_invitation_outbox WHERE id=?", Boolean.class, id)).isTrue();
        assertThat(SMTP.deliveryAttempts()).isZero();
    }

    @Test void lateSmtpCompletionCannotOverwriteAnotherWorkersLease() throws Exception {
        single(); UUID id = onlyCandidateDue(); UUID newerLease = UUID.randomUUID(); SMTP.pauseReceipt();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var pending = executor.submit(worker::processNext);
            try {
                assertThat(SMTP.awaitData()).isTrue();
                jdbc.update("UPDATE interview_invitation_outbox SET lease_token=? WHERE id=?", newerLease, id);
            } finally { SMTP.releaseReceipt(); }
            assertThat(pending.get(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(state(id)).isEqualTo("PROCESSING");
        assertThat(jdbc.queryForObject("SELECT lease_token FROM interview_invitation_outbox WHERE id=?", UUID.class, id)).isEqualTo(newerLease);
        assertThat(SMTP.messages()).hasSize(1);
    }

    @Test void explicitlySendingAValidInvitationIsIdempotentEvenAfterConfirmation() throws Exception {
        UUID interview = single();
        String token = candidateToken();
        UUID invitationId = jdbc.queryForObject("SELECT id FROM interview_invitations WHERE interview_id=?", UUID.class, interview);
        List<UUID> queued = jdbc.queryForList("SELECT id FROM interview_invitation_outbox ORDER BY id", UUID.class);
        noContent(sendInvitation(interview, 0, hrToken));
        var response = ok(call("POST", "/api/v1/public/interview-invitations/respond",
                json.writeValueAsString(Map.of("token", token, "action", "CONFIRM")), null));
        noContent(sendInvitation(interview, 0, hrToken));
        assertThat(jdbc.queryForObject("SELECT id FROM interview_invitations WHERE interview_id=? AND revoked_at IS NULL", UUID.class, interview))
                .isEqualTo(invitationId);
        assertThat(jdbc.queryForObject("SELECT status FROM interview_invitations WHERE id=?", String.class, invitationId)).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT responded_at FROM interview_invitations WHERE id=?", java.sql.Timestamp.class, invitationId).toInstant())
                .isEqualTo(Instant.parse(response.path("respondedAt").asText()));
        assertThat(jdbc.queryForList("SELECT id FROM interview_invitation_outbox ORDER BY id", UUID.class)).isEqualTo(queued);
        assertThat(SMTP.messages()).isEmpty();
    }

    @Test void explicitlySendingLegacyAppointmentCreatesItsFirstInvitationAndQueue() throws Exception {
        UUID interview = single();
        // Simulate an appointment created before this feature was enabled. Child mail rows cascade away.
        jdbc.update("DELETE FROM interview_invitations WHERE interview_id=?", interview);
        assertThat(count("interview_invitation_outbox")).isZero();
        noContent(sendInvitation(interview, 0, hrToken));
        assertThat(count("interview_sessions")).isEqualTo(1);
        assertThat(count("interview_events")).isEqualTo(1);
        assertThat(count("interview_invitations")).isEqualTo(1);
        assertThat(count("interview_invitation_outbox")).isEqualTo(2);
        assertThat(candidateToken()).matches("[A-Za-z0-9_-]{43}");
        assertThat(SMTP.messages()).isEmpty();
    }

    @Test void explicitlySendingExpiredInvitationRevokesOldCapabilityAndQueuesFreshOne() throws Exception {
        UUID interview = single(); String oldToken = candidateToken();
        UUID oldId = jdbc.queryForObject("SELECT id FROM interview_invitations WHERE interview_id=?", UUID.class, interview);
        jdbc.update("UPDATE interview_invitations SET expires_at=created_at+INTERVAL '1 second' WHERE id=?", oldId);
        try {
            clock.set(ApplicationTestSupport.NOW.plusSeconds(2));
            noContent(sendInvitation(interview, 0, hrToken));
            assertThat(candidateToken()).isNotEqualTo(oldToken);
            assertThat(count("interview_invitations")).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT revoked_at IS NOT NULL FROM interview_invitations WHERE id=?", Boolean.class, oldId)).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM interview_invitation_outbox WHERE status='CANCELLED' AND ciphertext IS NULL", Long.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM interview_invitation_outbox WHERE status='PENDING'", Long.class)).isEqualTo(2);
            var oldResponse = call("POST", "/api/v1/public/interview-invitations/lookup", json.writeValueAsString(Map.of("token", oldToken)), null);
            assertThat(oldResponse.statusCode()).as(oldResponse.body()).isEqualTo(400);
            assertThat(json.readTree(oldResponse.body()).path("code").asText()).isEqualTo("INTERVIEW_INVITATION_INVALID");
            assertThat(ok(call("POST", "/api/v1/public/interview-invitations/lookup",
                    json.writeValueAsString(Map.of("token", candidateToken())), null)).path("status").asText()).isEqualTo("PENDING");
        } finally { clock.set(ApplicationTestSupport.NOW); }
    }

    @Test void explicitlySendingWithStaleVersionCannotCreateOrResetInvitation() throws Exception {
        UUID interview = single();
        var result = sendInvitation(interview, 1, hrToken);
        assertThat(result.statusCode()).as(result.body()).isEqualTo(409);
        assertThat(json.readTree(result.body()).path("code").asText()).isEqualTo("INTERVIEW_VERSION_CHANGED");
        assertThat(count("interview_invitations")).isEqualTo(1);
        assertThat(count("interview_invitation_outbox")).isEqualTo(2);
    }

    @Test void explicitlySendingOutsideRecruiterScopeIsForbidden() throws Exception {
        UUID interview = single();
        var result = sendInvitation(interview, 0, token(outsider));
        assertThat(result.statusCode()).as(result.body()).isEqualTo(403);
        assertThat(json.readTree(result.body()).path("code").asText()).isEqualTo("FORBIDDEN");
        assertThat(count("interview_invitations")).isEqualTo(1);
        assertThat(count("interview_invitation_outbox")).isEqualTo(2);
        assertThat(SMTP.messages()).isEmpty();
    }

    private UUID single() throws Exception {
        return UUID.fromString(created(call("POST", "/api/v1/applications/" + first.applicationId() + "/interviews",
                json.writeValueAsString(schedule(START, panel)), hrToken)).path("id").asText());
    }
    private Map<String, Object> schedule(Instant startsAt, UUID participant) {
        var body = new LinkedHashMap<String, Object>();
        body.put("roundName", "Technical"); body.put("startsAt", startsAt.toString()); body.put("durationMinutes", 60);
        body.put("mode", "ONSITE"); body.put("room", "Room A"); body.put("participantIds", List.of(participant));
        body.put("preparationInstructions", "Mang CV và bài tập.");
        return body;
    }
    private void update(UUID interview, long version, Instant at, UUID participant) throws Exception {
        var body = schedule(at, participant); body.put("expectedVersion", version);
        ok(call("PUT", "/api/v1/interviews/" + interview, json.writeValueAsString(body), hrToken));
    }
    private JsonNode created(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return json.readTree(response.body());
    }
    private HttpResponse<String> sendInvitation(UUID interview, long version, String token) throws Exception {
        return call("POST", "/api/v1/interviews/" + interview + "/invitation",
                json.writeValueAsString(Map.of("expectedVersion", version)), token);
    }
    private void noContent(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(204);
        assertThat(response.body()).isEmpty();
        assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(value -> assertThat(value).contains("no-store"));
    }
    private String candidateToken() {
        String url = payloads().stream().filter(payload -> payload.email().equals("lan@example.test"))
                .findFirst().orElseThrow().replyUrl();
        return url.substring(url.indexOf("#token=") + 7);
    }
    private List<InterviewMailPayload> payloads() {
        return jdbc.query("SELECT id,interview_id,key_id,nonce,ciphertext FROM interview_invitation_outbox WHERE status='PENDING'",
                (row, number) -> crypto.decrypt(row.getObject("id", UUID.class), row.getObject("interview_id", UUID.class),
                        row.getString("key_id"), row.getBytes("nonce"), row.getBytes("ciphertext")));
    }
    private UUID onlyCandidateDue() {
        jdbc.update("UPDATE interview_invitation_outbox SET next_attempt_at=clock_timestamp()+INTERVAL '1 day' WHERE recipient_user_id IS NOT NULL");
        return jdbc.queryForObject("SELECT id FROM interview_invitation_outbox WHERE recipient_user_id IS NULL", UUID.class);
    }
    private void due(UUID id) { jdbc.update("UPDATE interview_invitation_outbox SET next_attempt_at=clock_timestamp()-INTERVAL '1 second' WHERE id=?", id); }
    private String state(UUID id) { return jdbc.queryForObject("SELECT status FROM interview_invitation_outbox WHERE id=?", String.class, id); }
    private void drain() {
        for (int index = 0; index < 20; index++) if (!worker.processNext()) return;
        throw new AssertionError("More messages than expected");
    }
    private MimeMessage messageTo(String email) throws Exception {
        for (MimeMessage message : SMTP.messages()) if (message.getAllRecipients()[0].toString().equals(email)) return message;
        throw new AssertionError("No synthetic message for " + email);
    }
    private static String plain(Part part) throws Exception {
        if (part.isMimeType("text/plain")) return String.valueOf(part.getContent());
        if (part.getContent() instanceof Multipart multipart) {
            var chunks = new ArrayList<String>();
            for (int i = 0; i < multipart.getCount(); i++) chunks.add(plain(multipart.getBodyPart(i)));
            return String.join("\n", chunks);
        }
        return "";
    }
}
