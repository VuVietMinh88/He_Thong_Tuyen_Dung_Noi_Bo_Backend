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

/** Task 526: personalised rejection previews with each candidate's real data, for applications all in scope and rejected. */
@TestPropertySource(properties = "app.candidate-mail.poll-delay-ms=3600000")
class RejectionPreviewIntegrationTest extends ScreeningTestSupport {
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
    void previewFillsEachCandidatesOwnLetterWithoutSending() throws Exception {
        JsonNode preview = ok(call("POST", REJECTIONS + "/preview", json.writeValueAsString(Map.of(
                "applicationIds", List.of(first.applicationId(), second.applicationId()),
                "subject", "Kết quả {{positionTitle}} cho {{candidateName}}")), recruiterToken));
        assertThat(preview.path("draft").asBoolean()).isTrue();
        assertThat(preview.path("entries")).hasSize(2);
        List<String> subjects = new ArrayList<>();
        preview.path("entries").forEach(entry -> subjects.add(entry.path("subject").asText()));
        assertThat(subjects).containsExactlyInAnyOrder("Kết quả Java cho " + ApplicationTestSupport.FULL_NAME,
                "Kết quả Java cho Trần Thị Bình");
        preview.path("entries").forEach(entry -> {
            assertThat(entry.path("text").asText()).contains("Xin chào " + entry.path("candidateName").asText() + ",");
            assertThat(entry.path("rejectionLetter").asText()).isEqualTo("NONE");
        });
        assertThat(count("email_messages")).isZero();
        assertThat(count("candidate_mail_outbox")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_access_events WHERE outcome='GRANTED'", Long.class))
                .isEqualTo(2);
    }

    @Test
    void previewNeedsEveryApplicationInScopeAndRejected() throws Exception {
        // RECRUITER A cannot read the other requisition: the whole batch is refused, nothing of it shown.
        var outside = call("POST", REJECTIONS + "/preview", json.writeValueAsString(Map.of(
                "applicationIds", List.of(first.applicationId(), other.applicationId()))), recruiterToken);
        assertThat(outside.statusCode()).isEqualTo(403);
        assertThat(outside.body()).doesNotContain(ApplicationTestSupport.FULL_NAME, "binh");
        jdbc.update("UPDATE job_applications SET stage='SCREENING' WHERE id=?", second.applicationId());
        var mixed = call("POST", REJECTIONS + "/preview", json.writeValueAsString(Map.of(
                "applicationIds", List.of(first.applicationId(), second.applicationId()))), hrToken);
        assertThat(mixed.statusCode()).isEqualTo(409);
        assertThat(json.readTree(mixed.body()).path("code").asText()).isEqualTo("REJECTION_BATCH_NOT_REJECTED");
        assertThat(json.readTree(mixed.body()).path("message").asText()).contains(second.applicationId().toString());
        var duplicate = call("POST", REJECTIONS + "/preview", json.writeValueAsString(Map.of(
                "applicationIds", List.of(first.applicationId(), first.applicationId()))), hrToken);
        assertThat(duplicate.statusCode()).isEqualTo(400);
        var unknown = call("POST", REJECTIONS + "/preview", json.writeValueAsString(Map.of(
                "applicationIds", List.of(UUID.randomUUID()))), hrToken);
        assertThat(unknown.statusCode()).isEqualTo(404);
        List<UUID> tooMany = new ArrayList<>();
        for (int i = 0; i < 101; i++) tooMany.add(UUID.randomUUID());
        assertThat(call("POST", REJECTIONS + "/preview", json.writeValueAsString(Map.of("applicationIds", tooMany)),
                hrToken).statusCode()).isEqualTo(400);
    }
}
