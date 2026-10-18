package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import vn.ttcs.recruitment.application.ApplicationConfirmationWorker;
import vn.ttcs.recruitment.application.ApplicationSubmission;
import vn.ttcs.recruitment.application.PostgresApplicationReceiptWriter;

import java.io.IOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ScreeningPrivacyIntegrationTest extends ScreeningTestSupport {
    private static final String FIRST_NOTE = "GHI_CHU_RIENG_PHONG_JAVA";
    private static final String OTHER_NOTE = "GHI_CHU_RIENG_PHONG_TAI_CHINH";
    private static final String OTHER_TAG = "NHAN_RIENG_PHONG_TAI_CHINH";
    private static final LocalSmtpServer SMTP = startSmtp();
    @Autowired PostgresApplicationReceiptWriter writer;
    @Autowired ApplicationConfirmationWorker worker;

    private static LocalSmtpServer startSmtp() {
        try { return new LocalSmtpServer(); } catch (IOException failure) { throw new IllegalStateException(failure); }
    }
    @DynamicPropertySource static void smtp(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", () -> "127.0.0.1"); registry.add("spring.mail.port", SMTP::port);
        registry.add("spring.mail.properties.mail.smtp.auth", () -> false);
        registry.add("spring.mail.properties.mail.smtp.starttls.enable", () -> false);
        registry.add("spring.mail.properties.mail.smtp.starttls.required", () -> false);
        registry.add("spring.mail.properties.mail.smtp.ssl.enable", () -> false);
        registry.add("app.mail.from", () -> "test@example.test");
    }
    @AfterAll static void closeSmtp() throws IOException { SMTP.close(); }

    @Test
    void unifiedProfileOnlyAggregatesAssessmentsFromReadableApplications() throws Exception {
        note(first.applicationId(), FIRST_NOTE);
        note(other.applicationId(), OTHER_NOTE);
        UUID privateTag = catalog("CANDIDATE_TAG", OTHER_TAG, true);
        ok(call("PUT", path(other.applicationId()) + "/tags", "{\"tagIds\":[\"" + privateTag + "\"]}", hrToken));
        ok(rate(other.applicationId(), hrToken, 1));
        String profile = "/api/v1/candidates/" + first.candidateId() + "/unified-profile";
        var own = ok(call("GET", profile, null, recruiterToken));
        assertThat(own.toString()).contains(FIRST_NOTE).doesNotContain(OTHER_NOTE, OTHER_TAG, other.applicationId().toString());
        assertThat(own.path("applications").size()).isEqualTo(1);
        assertThat(own.path("applications").get(0).path("screening").path("applicationId").asText()).isEqualTo(first.applicationId().toString());
        assertThat(own.path("notes").get(0).path("applicationId").asText()).isEqualTo(first.applicationId().toString());
        var all = ok(call("GET", profile, null, hrToken));
        assertThat(all.toString()).contains(FIRST_NOTE, OTHER_NOTE, OTHER_TAG);
        var otherView = ok(call("GET", profile, null, token(outsider)));
        assertThat(otherView.toString()).contains(OTHER_NOTE).doesNotContain(FIRST_NOTE, first.applicationId().toString());
    }

    @Test
    void candidateLookupNeverIncludesInternalContentOrStaff() throws Exception {
        note(first.applicationId(), FIRST_NOTE);
        ok(rate(first.applicationId(), hrToken, 5));
        ok(call("PUT", path(first.applicationId()) + "/tags", "{\"tagIds\":[\"" + tag + "\"]}", hrToken));
        var response = applications.lookup(first.trackingCode(), "lan@example.test");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body()).doesNotContain(FIRST_NOTE, "Nhãn nội bộ", "HR Nội Bộ", "screening", "notes", "rating", "authorId");
        assertThat(call("GET", path(first.applicationId()), null, null).statusCode()).isEqualTo(401);
    }

    @Test
    void referrerStatusRemainsGeneralEvenWhenApplicationHasAssessment() throws Exception {
        UUID posting = applications.publishedPosting("Referral");
        var receipt = applications.refer(recruiterToken, posting, "referred@example.test");
        assertThat(receipt.statusCode()).as(receipt.body()).isEqualTo(201);
        UUID referred = jdbc.queryForObject("SELECT id FROM job_applications WHERE email='referred@example.test'", UUID.class);
        note(referred, OTHER_NOTE);
        ok(rate(referred, hrToken, 2));
        var status = applications.myReferrals(recruiterToken);
        assertThat(status.statusCode()).as(status.body()).isEqualTo(200);
        assertThat(status.body()).doesNotContain(OTHER_NOTE, "screening", "rating", "notes", "authorId", "HR Nội Bộ");
    }

    @Test
    void actualCandidateConfirmationEmailOmitsAssessmentAddedBeforeSending() throws Exception {
        SMTP.reset();
        UUID posting = applications.publishedPosting("Email privacy");
        var receipt = writer.accept(posting, new ApplicationSubmission("Ứng viên", "mail@example.test", "0912345678", null, null), applications.pdf());
        note(receipt.applicationId(), FIRST_NOTE);
        ok(rate(receipt.applicationId(), hrToken, 4));
        assertThat(worker.processNext()).isTrue();
        assertThat(SMTP.messages()).hasSize(1);
        String body = SMTP.messages().getFirst().getContent().toString();
        assertThat(body).contains("Email privacy", "Ứng viên").doesNotContain(FIRST_NOTE, "rating", "HR Nội Bộ", "Nhãn nội bộ");
    }

    private void note(UUID id, String text) throws Exception {
        ok(call("POST", path(id) + "/notes", "{\"content\":\"" + text + "\"}", hrToken));
    }
}
