package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import vn.ttcs.recruitment.account.Role;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP and disposable PostgreSQL: an entire batch and its invitation queue commit together. */
@TestPropertySource(properties = {
        "app.interview-invitation.enabled=true",
        "app.interview-invitation.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.interview-invitation.page-url=http://localhost:8080/api/v1/public/interview-invitations/reply",
        "app.interview-invitation.delivery-enabled=false",
        "app.interview-invitation.poll-delay-ms=3600000"
})
class InterviewBulkSchedulingIntegrationTest extends ScreeningTestSupport {
    private static final Instant START = ApplicationTestSupport.NOW.plusSeconds(7200);
    private UUID panel;

    @BeforeEach
    void panelFixture() {
        panel = applications.account("Bulk interviewer", "bulk-panel@example.test", Role.INTERVIEWER);
    }

    @Test
    void previewPreservesApplicationOrderAndNeverSavesAppointmentsOrBatch() throws Exception {
        var preview = ok(post("/preview", body(List.of(other.applicationId(), first.applicationId())), hrToken));
        assertThat(preview.path("canCreate").asBoolean()).isTrue();
        assertThat(preview.path("slots")).hasSize(2);
        assertThat(preview.path("slots").get(0).path("applicationId").asText()).isEqualTo(other.applicationId().toString());
        assertThat(preview.path("slots").get(0).path("startsAt").asText()).isEqualTo(START.toString());
        assertThat(preview.path("slots").get(1).path("startsAt").asText()).isEqualTo(START.plusSeconds(3600).toString());
        assertThat(preview.path("slots").get(1).path("endsAt").asText()).isEqualTo(START.plusSeconds(7200).toString());
        assertThat(preview.toString()).doesNotContain("email", "fullName", "token", "candidateId");
        assertNothingScheduled();
    }














    private Map<String, Object> body(List<UUID> applications) {
        var body = new LinkedHashMap<String, Object>();
        body.put("requestId", UUID.randomUUID()); body.put("applicationIds", applications);
        body.put("roundName", "Vòng chuyên môn"); body.put("windowStart", START.toString());
        body.put("windowEnd", START.plusSeconds(applications.size() * 3600L).toString());
        body.put("durationMinutes", 60); body.put("mode", "ONSITE"); body.put("room", "Bulk room");
        body.put("participantIds", List.of(panel));
        return body;
    }

    private HttpResponse<String> post(String suffix, Map<String, Object> body, String token) throws Exception {
        return call("POST", "/api/v1/interviews/bulk" + suffix, json.writeValueAsString(body), token);
    }

    private void single(Instant start) throws Exception {
        var body = Map.of("roundName", "Booked", "startsAt", start.toString(), "durationMinutes", 60,
                "mode", "ONSITE", "room", "Bulk room", "participantIds", List.of(panel));
        created(call("POST", "/api/v1/applications/" + first.applicationId() + "/interviews", json.writeValueAsString(body), hrToken));
    }

    private JsonNode created(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(value -> assertThat(value).contains("no-store"));
        return json.readTree(response.body());
    }

    private void error(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo(code);
    }

    private void assertNothingScheduled() {
        assertThat(count("interview_sessions")).isZero();
        assertThat(count("interview_events")).isZero();
        assertThat(count("interview_bulk_requests")).isZero();
    }
}
