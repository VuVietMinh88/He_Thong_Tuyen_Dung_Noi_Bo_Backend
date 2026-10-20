package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Task 532: template management and the personalised preview with real, scoped application data. */
class EmailTemplateApiIntegrationTest extends ScreeningTestSupport {
    private static final String TEMPLATES = "/api/v1/messaging/templates";
    EmailTemplateTestData seeded;

    @BeforeEach void keepSeed() { seeded = new EmailTemplateTestData(jdbc); jdbc.execute("TRUNCATE candidate_access_events"); }
    @AfterEach void restoreSeed() { seeded.restore(); }

    @Test
    void templateReadersSeeEveryTypeWithItsVariablesAndOthersAreForbidden() throws Exception {
        JsonNode list = ok(call("GET", TEMPLATES, null, hrToken));
        assertThat(list).hasSize(5);
        JsonNode thanks = null;
        for (JsonNode template : list) if (template.path("type").asText().equals("INTERVIEW_THANKS")) thanks = template;
        assertThat(thanks).isNotNull();
        assertThat(thanks.path("autoSend").asBoolean()).isFalse();
        var names = new java.util.ArrayList<String>();
        thanks.path("variables").forEach(variable -> names.add(variable.path("name").asText()));
        assertThat(names).containsExactly("candidateName", "positionTitle", "interviewTime", "companyName");
        assertThat(thanks.path("mandatoryContent").asText()).isNotBlank();
        assertThat(ok(call("GET", TEMPLATES + "/OFFER", null, recruiterToken)).path("subject").asText()).isEqualTo("Thư mời nhận việc");
        error(call("GET", TEMPLATES + "/offer", null, hrToken), 404, "EMAIL_TEMPLATE_NOT_FOUND");
        // A hiring manager reads only its own notifications (NOTIFICATIONS_READ_SCOPED), not company settings.
        assertThat(call("GET", TEMPLATES, null, token(manager)).statusCode()).isEqualTo(403);
    }

    @Test
    void saveReplacesTheTextAndFlagWithAVersionCheckAndRefusesUnknownVariablesAndHeaderBreaks() throws Exception {
        JsonNode saved = ok(save("REJECTION", 0, "Kết quả ứng tuyển {{positionTitle}}",
                "Chào {{candidateName}},\r\n\r\nRất tiếc.", true, hrToken));
        assertThat(saved.path("version").asLong()).isEqualTo(1);
        assertThat(saved.path("autoSend").asBoolean()).isTrue();
        assertThat(saved.path("body").asText()).isEqualTo("Chào {{candidateName}},\n\nRất tiếc.");
        assertThat(saved.path("updatedByName").asText()).isEqualTo("HR Nội Bộ");
        error(save("REJECTION", 0, "Cũ", "Cũ", false, hrToken), 409, "EMAIL_TEMPLATE_VERSION_CONFLICT");

        var unknown = save("REJECTION", 1, "Kết quả", "Lịch {{interviewTime}} lương {{salary}}", true, hrToken);
        error(unknown, 400, "EMAIL_TEMPLATE_INVALID");
        assertThat(json.readTree(unknown.body()).path("fieldErrors").path("body").asText()).contains("interviewTime", "salary");
        error(save("REJECTION", 1, "Kết quả {{candidate}}", "Nội dung", true, hrToken), 400, "EMAIL_TEMPLATE_INVALID");
        var header = save("REJECTION", 1, "Kết quả\r\nBcc: spy@example.test", "Nội dung", true, hrToken);
        error(header, 400, "VALIDATION_ERROR");
        assertThat(json.readTree(header.body()).path("fieldErrors").has("subject")).isTrue();
        error(call("PUT", TEMPLATES + "/REJECTION", "{\"expectedVersion\":1,\"subject\":\"A\",\"body\":\"B\","
                + "\"autoSend\":\"true\"}", hrToken), 400, "INVALID_JSON");
        error(call("PUT", TEMPLATES + "/REJECTION", "{\"expectedVersion\":1,\"subject\":\"A\",\"body\":\"B\","
                + "\"autoSend\":true,\"supportedVariables\":[\"salary\"]}", hrToken), 400, "INVALID_JSON");
        // RECRUITER holds NOTIFICATIONS_WRITE_ALL in the seed; a hiring manager does not.
        assertThat(save("REJECTION", 1, "Kết quả", "Nội dung", false, recruiterToken).statusCode()).isEqualTo(200);
        assertThat(save("REJECTION", 2, "Kết quả", "Nội dung", false, token(manager)).statusCode()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT version FROM email_templates WHERE template_type='REJECTION'", Long.class))
                .isEqualTo(2);
        error(save("UNKNOWN", 0, "A", "B", true, hrToken), 404, "EMAIL_TEMPLATE_NOT_FOUND");
    }

    @Test
    void previewFillsAnUnsavedDraftWithTheRealCandidateEscapesHtmlAndLogsTheRead() throws Exception {
        JsonNode preview = ok(preview("REJECTION", Map.of("applicationId", first.applicationId(),
                "subject", "Kết quả vị trí {{positionTitle}}",
                "body", "Chào {{candidateName}},\n\nLương < 20 triệu & <3 tại {{companyName}}."), recruiterToken));
        assertThat(preview.path("draft").asBoolean()).isTrue();
        assertThat(preview.path("subject").asText()).isEqualTo("Kết quả vị trí Java");
        assertThat(preview.path("text").asText()).contains("Chào " + ApplicationTestSupport.FULL_NAME + ",",
                "Lương < 20 triệu & <3 tại");
        assertThat(preview.path("html").asText()).contains("Lương &lt; 20 triệu &amp; &lt;3").doesNotContain("< 20");
        // The draft is never saved.
        assertThat(jdbc.queryForObject("SELECT version FROM email_templates WHERE template_type='REJECTION'", Long.class)).isZero();
        assertThat(jdbc.queryForList("SELECT access_kind, outcome, application_id FROM candidate_access_events"))
                .containsExactly(Map.of("access_kind", "PROFILE", "outcome", "GRANTED", "application_id", first.applicationId()));
    }

    @Test
    void receiptAndOfferPreviewsKeepTheMandatoryPartWithoutCodesLinksOrTerms() throws Exception {
        JsonNode receipt = ok(preview("APPLICATION_RECEIPT", Map.of("applicationId", first.applicationId()), hrToken));
        assertThat(receipt.path("draft").asBoolean()).isFalse();
        assertThat(receipt.path("html").isNull()).isTrue();
        assertThat(receipt.path("subject").asText()).isEqualTo("Xác nhận nhận hồ sơ ứng tuyển — Hệ thống tuyển dụng nội bộ");
        assertThat(receipt.path("text").asText()).startsWith("Chào " + ApplicationTestSupport.FULL_NAME
                        + ",\n\nHệ thống đã nhận hồ sơ ứng tuyển của bạn.\n\nVị trí ứng tuyển: Java\n")
                .contains("Mã tra cứu: [mã tra cứu riêng của ứng viên]", "[liên kết tra cứu riêng của ứng viên]",
                        "[liên kết rút hồ sơ riêng của ứng viên]")
                .doesNotContain("#ma=", "#rut=", "https://");

        JsonNode offer = ok(preview("OFFER", Map.of("applicationId", first.applicationId()), hrToken));
        assertThat(offer.path("text").asText()).contains("Lương cơ bản hằng tháng: [theo offer đã được duyệt]",
                "[Phần riêng của ứng viên, chỉ có trong email thật: liên kết chấp nhận hoặc từ chối lời mời.]")
                .doesNotContain("#token=", "VND\n");
    }

    @Test
    void interviewPreviewsUseTheChosenOrNextInterviewAndNeedOne() throws Exception {
        error(preview("INTERVIEW_THANKS", Map.of("applicationId", first.applicationId()), hrToken), 409,
                "EMAIL_PREVIEW_INTERVIEW_REQUIRED");
        UUID past = interview(first.applicationId(), ApplicationTestSupport.NOW.minusSeconds(86_400 * 2), "Vòng 1");
        UUID next = interview(first.applicationId(), ApplicationTestSupport.NOW.plusSeconds(86_400), "Vòng 2");
        JsonNode thanks = ok(preview("INTERVIEW_THANKS", Map.of("applicationId", first.applicationId()), hrToken));
        assertThat(thanks.path("interviewId").asText()).isEqualTo(next.toString());
        var chosen = new LinkedHashMap<String, Object>();
        chosen.put("applicationId", first.applicationId());
        chosen.put("interviewId", past);
        JsonNode invitation = ok(preview("INTERVIEW_INVITATION", chosen, hrToken));
        assertThat(invitation.path("interviewId").asText()).isEqualTo(past.toString());
        assertThat(invitation.path("text").asText()).contains("Bạn được mời tham gia buổi phỏng vấn", "Vòng phỏng vấn: Vòng 1",
                "Địa điểm: Phòng họp A", "[Phần riêng của ứng viên, chỉ có trong email thật: liên kết phản hồi lời mời.]");
        chosen.put("interviewId", interview(other.applicationId(), ApplicationTestSupport.NOW.plusSeconds(3600), "Khác"));
        error(preview("INTERVIEW_INVITATION", chosen, hrToken), 404, "INTERVIEW_NOT_FOUND");
    }

    @Test
    void previewChecksTheApplicationScopeAndTheDraftBeforeShowingAnything() throws Exception {
        // RECRUITER B is assigned to the other requisition only: 403, logged as a refused read.
        assertThat(preview("REJECTION", Map.of("applicationId", first.applicationId()), token(outsider)).statusCode())
                .isEqualTo(403);
        assertThat(jdbc.queryForList("SELECT outcome, access_scope FROM candidate_access_events"))
                .containsExactly(Map.of("outcome", "DENIED", "access_scope", "SCOPED"));
        error(preview("REJECTION", Map.of("applicationId", UUID.randomUUID()), hrToken), 404, "APPLICATION_NOT_FOUND");
        error(preview("REJECTION", Map.of("applicationId", first.applicationId(), "body", "{{salary}}"), hrToken), 400,
                "EMAIL_TEMPLATE_INVALID");
        error(preview("NOPE", Map.of("applicationId", first.applicationId()), hrToken), 404, "EMAIL_TEMPLATE_NOT_FOUND");
        error(call("POST", TEMPLATES + "/REJECTION/preview", "{}", hrToken), 400, "VALIDATION_ERROR");
        // The hiring manager reads the candidate but holds no template permission.
        assertThat(preview("REJECTION", Map.of("applicationId", first.applicationId()), token(manager)).statusCode())
                .isEqualTo(403);
    }

    private HttpResponse<String> save(String type, long version, String subject, String body, boolean autoSend,
                                      String token) throws Exception {
        return call("PUT", TEMPLATES + "/" + type, json.writeValueAsString(Map.of("expectedVersion", version,
                "subject", subject, "body", body, "autoSend", autoSend)), token);
    }

    private HttpResponse<String> preview(String type, Map<String, Object> body, String token) throws Exception {
        return call("POST", TEMPLATES + "/" + type + "/preview", json.writeValueAsString(body), token);
    }

    private void error(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo(code);
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
    }

    UUID interview(UUID application, Instant start, String round) {
        UUID id = UUID.randomUUID();
        Timestamp at = Timestamp.from(ApplicationTestSupport.NOW);
        jdbc.update("""
                INSERT INTO interview_sessions(id,application_id,round_name,starts_at,ends_at,mode,room,normalized_room,
                    status,created_by,created_at,updated_at)
                VALUES (?,?,?,?,?,'ONSITE','Phòng họp A','phòng họp a','SCHEDULED',?,?,?)
                """, id, application, round, Timestamp.from(start), Timestamp.from(start.plusSeconds(3600)), hr, at, at);
        return id;
    }
}
