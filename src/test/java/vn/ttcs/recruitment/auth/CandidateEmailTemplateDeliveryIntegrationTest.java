package vn.ttcs.recruitment.auth;

import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.application.ApplicationConfirmationWorker;
import vn.ttcs.recruitment.application.ApplicationSubmission;
import vn.ttcs.recruitment.application.PostgresApplicationReceiptWriter;
import vn.ttcs.recruitment.invitation.InterviewInvitationWorker;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 533: the templates and their automatic-send flags in the real send paths of the receipt, the interview
 * invitation and the offer, over a loopback SMTP server and a disposable PostgreSQL.
 */
@TestPropertySource(properties = {
        "app.offers.delivery-enabled=true",
        "app.interview-invitation.enabled=true", "app.interview-invitation.delivery-enabled=true",
        "app.interview-invitation.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.interview-invitation.page-url=http://localhost:8080/api/v1/public/interview-invitations/reply",
        "app.interview-invitation.poll-delay-ms=3600000"
})
class CandidateEmailTemplateDeliveryIntegrationTest extends OfferDeliveryTestSupport {
    private static final LocalSmtpServer SMTP = smtpServer();
    private static final Instant START = ApplicationTestSupport.NOW.plusSeconds(86400);
    @Autowired PostgresApplicationReceiptWriter receipts;
    @Autowired ApplicationConfirmationWorker confirmations;
    @Autowired InterviewInvitationWorker invitations;
    EmailTemplateTestData seeded;
    UUID panel;

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

    @BeforeEach void templates() {
        SMTP.reset();
        seeded = new EmailTemplateTestData(jdbc);
        panel = applications.account("Interview panel", "panel@example.test", Role.INTERVIEWER);
    }
    @AfterEach void restore() { seeded.restore(); }

    @Test
    void receiptUsesTheTemplateAndKeepsTheTrackingAndWithdrawalLinks() throws Exception {
        template("APPLICATION_RECEIPT", "Đã nhận hồ sơ {{positionTitle}}",
                "Kính gửi {{candidateName}},\n\nCảm ơn bạn đã quan tâm tới {{companyName}}.");
        var receipt = receipts.accept(applications.publishedPosting("Kiểm thử"),
                new ApplicationSubmission("Trần Thị Mai", "mai@example.test", ApplicationTestSupport.PHONE, null, null),
                applications.pdf());
        assertThat(message(receipt.applicationId(), "APPLICATION_RECEIPT"))
                .containsEntry("status", "QUEUED").containsEntry("delivery", "APPLICATION_CONFIRMATION")
                .containsEntry("recipient_email", "mai@example.test");

        assertThat(confirmations.processNext()).isTrue();
        MimeMessage mail = SMTP.messages().getFirst();
        assertThat(mail.getSubject()).isEqualTo("Đã nhận hồ sơ Kiểm thử");
        assertThat(String.valueOf(mail.getContent()).replace("\r\n", "\n")).startsWith("Kính gửi Trần Thị Mai,\n\nCảm ơn bạn đã quan tâm tới")
                .contains("Mã tra cứu: " + receipt.trackingCode(), receipt.trackingUrl(),
                        "https://portal.example.test/tra-cuu#rut=" + receipt.trackingCode())
                .doesNotContain("{{");
        var sent = message(receipt.applicationId(), "APPLICATION_RECEIPT");
        assertThat(sent).containsEntry("status", "SENT");
        assertThat(sent.get("sent_at")).isNotNull();
    }

    @Test
    void receiptWithAutomaticSendingOffIsSuppressedAndNeverQueued() throws Exception {
        seeded.autoSend("APPLICATION_RECEIPT", false);
        var receipt = receipts.accept(applications.publishedPosting("Tắt gửi"),
                new ApplicationSubmission("Lê Văn Bình", "binh@example.test", ApplicationTestSupport.PHONE, null, null),
                applications.pdf());
        assertThat(receipt.trackingCode()).isNotBlank();
        assertThat(message(receipt.applicationId(), "APPLICATION_RECEIPT")).containsEntry("status", "SUPPRESSED")
                .containsEntry("delivery", null).containsEntry("sent_at", null);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_email_outbox WHERE application_id=?", Long.class,
                receipt.applicationId())).isZero();
        assertThat(confirmations.processNext()).isFalse();
        assertThat(SMTP.messages()).isEmpty();
    }

    @Test
    void candidateInvitationUsesTheTemplateWhileStaffAndDetailsKeepTheFixedText() throws Exception {
        template("INTERVIEW_INVITATION", "Mời {{candidateName}} phỏng vấn lúc {{interviewTime}}",
                "Thân gửi {{candidateName}},\n\nMời bạn phỏng vấn vị trí {{positionTitle}}.");
        UUID interview = schedule();
        assertThat(message(first.applicationId(), "INTERVIEW_INVITATION")).containsEntry("status", "QUEUED")
                .containsEntry("variant", "INVITATION").containsEntry("interview_id", interview);
        drainInvitations();
        assertThat(SMTP.messages()).hasSize(2);
        MimeMessage candidate = to("lan@example.test");
        assertThat(candidate.getSubject()).isEqualTo("Mời " + ApplicationTestSupport.FULL_NAME + " phỏng vấn lúc "
                + vn.ttcs.recruitment.messaging.CandidateMailFormat.interviewTime(START, java.time.ZoneId.of("Asia/Ho_Chi_Minh")));
        assertThat(plain(candidate)).contains("Thân gửi " + ApplicationTestSupport.FULL_NAME + ",",
                "Mời bạn phỏng vấn vị trí Java.", "Room A", "Mang CV và bài tập.", "#token=")
                .doesNotContain("Bạn được mời tham gia buổi phỏng vấn");
        MimeMessage staff = to("panel@example.test");
        assertThat(staff.getSubject()).isEqualTo("Thư mời phỏng vấn");
        assertThat(plain(staff)).contains("Bạn được mời tham gia buổi phỏng vấn").doesNotContain("Thân gửi", "#token=");
        assertThat(message(first.applicationId(), "INTERVIEW_INVITATION")).containsEntry("status", "SENT");
    }

    @Test
    void invitationWithAutomaticSendingOffIsSuppressedButAScheduleChangeStillReachesTheCandidate() throws Exception {
        seeded.autoSend("INTERVIEW_INVITATION", false);
        UUID interview = schedule();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM interview_invitation_outbox WHERE recipient_user_id IS NULL",
                Long.class)).isZero();
        assertThat(message(first.applicationId(), "INTERVIEW_INVITATION")).containsEntry("status", "SUPPRESSED")
                .containsEntry("variant", "INVITATION");
        var change = scheduleBody(START.plusSeconds(3600));
        change.put("expectedVersion", 0);
        ok(call("PUT", "/api/v1/interviews/" + interview, json.writeValueAsString(change), hrToken));
        assertThat(jdbc.queryForList("SELECT variant, status FROM email_messages WHERE template_type='INTERVIEW_INVITATION'"
                + " ORDER BY created_at, variant")).containsExactly(Map.of("variant", "INVITATION", "status", "SUPPRESSED"),
                Map.of("variant", "UPDATED", "status", "QUEUED"));
        drainInvitations();
        assertThat(to("lan@example.test").getSubject()).isEqualTo("Cập nhật lịch phỏng vấn");
    }

    @Test
    void offerUsesTheTemplateAndKeepsTermsAndReplyLink() throws Exception {
        template("OFFER", "Offer vị trí {{positionTitle}} cho {{candidateName}}",
                "Chúc mừng {{candidateName}},\n\nCông ty gửi bạn lời mời nhận việc.");
        Sent sent = queued();
        assertThat(message(first.applicationId(), "OFFER")).containsEntry("status", "QUEUED")
                .containsEntry("offer_id", sent.id()).containsEntry("trigger_kind", "MANUAL").containsEntry("created_by", hr);
        due(sent.id());
        assertThat(worker.processNext()).isTrue();
        MimeMessage mail = SMTP.messages().getFirst();
        assertThat(mail.getSubject()).isEqualTo("Offer vị trí Developer cho " + ApplicationTestSupport.FULL_NAME);
        assertThat(plain(mail)).contains("Chúc mừng " + ApplicationTestSupport.FULL_NAME + ",", "Công ty gửi bạn lời mời",
                "Lương cơ bản hằng tháng: 20.000.000 VND", "#token=" + sent.token())
                .doesNotContain("Bạn nhận được lời mời nhận việc với thông tin dưới đây.");
        assertThat(message(first.applicationId(), "OFFER")).containsEntry("status", "SENT");
    }

    @Test
    void offerWithAutomaticSendingOffIsRefusedWithoutAFakeSentStatus() throws Exception {
        seeded.autoSend("OFFER", false);
        UUID offer = approved();
        error(send(offer, 0, hrToken), 409, "OFFER_EMAIL_DISABLED");
        assertThat(state(offer)).isEqualTo("APPROVED");
        assertThat(count("offer_delivery_outbox")).isZero();
        assertThat(count("offer_response_tokens")).isZero();
        assertThat(count("email_messages")).isZero();
    }

    private void template(String type, String subject, String body) {
        jdbc.update("UPDATE email_templates SET subject=?, body=? WHERE template_type=?", subject, body, type);
    }

    private Map<String, Object> message(UUID application, String type) {
        return jdbc.queryForMap("SELECT * FROM email_messages WHERE application_id=? AND template_type=?"
                + " ORDER BY created_at DESC LIMIT 1", application, type);
    }

    private UUID schedule() throws Exception {
        var response = call("POST", "/api/v1/applications/" + first.applicationId() + "/interviews",
                json.writeValueAsString(scheduleBody(START)), hrToken);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return UUID.fromString(json.readTree(response.body()).path("id").asText());
    }

    private LinkedHashMap<String, Object> scheduleBody(Instant startsAt) {
        var body = new LinkedHashMap<String, Object>();
        body.put("roundName", "Technical"); body.put("startsAt", startsAt.toString()); body.put("durationMinutes", 60);
        body.put("mode", "ONSITE"); body.put("room", "Room A"); body.put("participantIds", List.of(panel));
        body.put("preparationInstructions", "Mang CV và bài tập.");
        return body;
    }

    private void drainInvitations() {
        for (int index = 0; index < 20; index++) if (!invitations.processNext()) return;
        throw new AssertionError("More messages than expected");
    }

    private MimeMessage to(String email) throws Exception {
        for (MimeMessage message : SMTP.messages()) if (message.getAllRecipients()[0].toString().equals(email)) return message;
        throw new AssertionError("No synthetic message for " + email);
    }

    static String plain(Part part) throws Exception {
        if (part.isMimeType("text/plain")) return String.valueOf(part.getContent());
        if (part.getContent() instanceof Multipart multipart) {
            var chunks = new ArrayList<String>();
            for (int i = 0; i < multipart.getCount(); i++) chunks.add(plain(multipart.getBodyPart(i)));
            return String.join("\n", chunks);
        }
        return "";
    }
}
