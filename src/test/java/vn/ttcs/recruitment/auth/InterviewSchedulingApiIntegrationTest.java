package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import vn.ttcs.recruitment.account.Role;

import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real HTTP and PostgreSQL tests, sharing the existing synthetic application fixture only. */
class InterviewSchedulingApiIntegrationTest extends ScreeningTestSupport {
    private UUID panel, otherPanel;
    private String panelToken;
    private static final Instant START = ApplicationTestSupport.NOW.plusSeconds(7200);

    @BeforeEach
    void panelFixture() {
        panel = applications.account("Interviewer One", "interviewer-one@example.test", Role.INTERVIEWER);
        otherPanel = applications.account("Interviewer Two", "interviewer-two@example.test", Role.INTERVIEWER);
        panelToken = token(panel);
    }

    @Test
    void createStoresAppointmentPanelAndImmutableEventWithoutMovingApplication() throws Exception {
        JsonNode created = created(create(first.applicationId(), recruiterToken, schedule(START, panel, "Phòng 1")));
        UUID id = UUID.fromString(created.path("id").asText());
        assertThat(created.path("applicationId").asText()).isEqualTo(first.applicationId().toString());
        assertThat(created.path("startsAt").asText()).isEqualTo(START.toString());
        assertThat(created.path("endsAt").asText()).isEqualTo(START.plusSeconds(3600).toString());
        assertThat(created.path("version").asLong()).isZero();
        assertThat(created.path("participantIds").get(0).asText()).isEqualTo(panel.toString());
        assertThat(jdbc.queryForObject("SELECT stage FROM job_applications WHERE id=?", String.class, first.applicationId())).isEqualTo("NEW");
        assertThat(jdbc.queryForObject("SELECT event_type FROM interview_events WHERE interview_id=?", String.class, id)).isEqualTo("INTERVIEW_SCHEDULED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_access_events WHERE application_id=? AND outcome='GRANTED'", Long.class,
                first.applicationId())).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("UPDATE interview_events SET round_name='Changed' WHERE interview_id=?", id))
                .rootCause().hasMessageContaining("interview_events rows are never changed");
    }


    @Test
    void invitedPanelReadsOwnLogisticsWithoutCandidateCvOrOtherApplicationAccess() throws Exception {
        JsonNode created = created(create(first.applicationId(), hrToken, schedule(START, panel, "Room")));
        String id = created.path("id").asText();
        JsonNode own = ok(call("GET", "/api/v1/interviews/" + id, null, panelToken));
        assertThat(own.toString()).doesNotContain("lan@example.test", "salary", "tracking", "password", "fullName");
        assertThat(ok(call("GET", "/api/v1/interviews/me", null, panelToken)).path("totalElements").asLong()).isEqualTo(1);
        assertThat(ok(call("GET", "/api/v1/interviews/me", null, token(otherPanel))).path("totalElements").asLong()).isZero();
        error(call("GET", "/api/v1/interviews/" + id, null, token(otherPanel)), 403, "FORBIDDEN");
        error(call("GET", collection(first.applicationId()), null, panelToken), 403, "FORBIDDEN");
        error(call("GET", "/api/v1/candidates/" + first.candidateId(), null, panelToken), 403, "FORBIDDEN");
    }

    @Test
    void managerMayReadManagedApplicationButCannotScheduleWithoutWritePermission() throws Exception {
        created(create(first.applicationId(), hrToken, schedule(START, panel, "Room")));
        assertThat(ok(call("GET", collection(first.applicationId()), null, token(manager))).path("totalElements").asLong()).isEqualTo(1);
        error(create(first.applicationId(), token(manager), schedule(START, panel, "Room")), 403, "FORBIDDEN");
        error(call("GET", collection(other.applicationId()), null, token(manager)), 403, "FORBIDDEN");
    }

    @Test
    void unknownIdsAndMalformedPagingUseExpectedErrors() throws Exception {
        error(call("GET", "/api/v1/interviews/" + UUID.randomUUID(), null, hrToken), 404, "INTERVIEW_NOT_FOUND");
        error(call("GET", collection(UUID.randomUUID()), null, panelToken), 404, "APPLICATION_NOT_FOUND");
        error(call("GET", collection(first.applicationId()) + "?page=-1", null, hrToken), 400, "VALIDATION_ERROR");
        error(call("GET", "/api/v1/interviews/me?size=101", null, panelToken), 400, "VALIDATION_ERROR");
        error(call("GET", "/api/v1/interviews/me", null, null), 401, "UNAUTHORIZED");
    }

    @Test
    void requiredDatesDurationModeAndParticipantsAreValidatedBeforeSaving() throws Exception {
        for (Map<String, Object> patch : List.of(Map.<String, Object>of("durationMinutes", 0),
                Map.<String, Object>of("durationMinutes", 481), Map.<String, Object>of("startsAt", ApplicationTestSupport.NOW.toString()),
                Map.<String, Object>of("participantIds", List.of()), Map.<String, Object>of("roundName", " "),
                Map.<String, Object>of("participantIds", List.of(panel, panel)), Map.<String, Object>of("mode", "UNKNOWN"))) {
            Map<String, Object> body = schedule(START, panel, "Room"); body.putAll(patch);
            assertThat(create(first.applicationId(), hrToken, body).statusCode()).as(patch.toString()).isEqualTo(400);
        }
        error(call("POST", collection(first.applicationId()), "{}", hrToken), 400, "VALIDATION_ERROR");
        assertThat(count("interview_sessions")).isZero();
        assertThat(count("interview_events")).isZero();
    }

    @Test
    void onlineRequiresHttpsAndOnsiteRequiresOnlyRoom() throws Exception {
        Map<String, Object> online = schedule(START, panel, null);
        online.put("mode", "ONLINE"); online.put("meetingUrl", "https://meet.example.test/room");
        JsonNode created = created(create(first.applicationId(), hrToken, online));
        assertThat(created.path("meetingUrl").asText()).isEqualTo("https://meet.example.test/room");
        for (String url : List.of("http://meet.example.test/room", "javascript:alert(1)", "https://user:secret@meet.example.test/a")) {
            online.put("meetingUrl", url);
            error(create(first.applicationId(), hrToken, online), 400, "VALIDATION_ERROR");
        }
        error(create(first.applicationId(), hrToken, schedule(START, panel, null)), 400, "VALIDATION_ERROR");
        var both = schedule(START, panel, "Room"); both.put("meetingUrl", "https://meet.example.test/a");
        error(create(first.applicationId(), hrToken, both), 400, "VALIDATION_ERROR");
    }

    @Test
    void participantMustExistBeEnabledAndHoldEvaluationWritePermission() throws Exception {
        for (UUID invalid : List.of(UUID.randomUUID(), recruiter, manager)) {
            error(create(first.applicationId(), hrToken, schedule(START, invalid, "Room")), 400, "VALIDATION_ERROR");
        }
        jdbc.update("UPDATE user_accounts SET enabled=false WHERE id=?", panel);
        error(create(first.applicationId(), hrToken, schedule(START, panel, "Room")), 400, "VALIDATION_ERROR");
        jdbc.update("UPDATE user_accounts SET enabled=true,admin_locked_at=?,admin_lock_reason='Test lock',admin_locked_by=? WHERE id=?",
                Timestamp.from(ApplicationTestSupport.NOW), hr, panel);
        error(create(first.applicationId(), hrToken, schedule(START, panel, "Room")), 400, "VALIDATION_ERROR");
        assertThat(count("interview_sessions")).isZero();
    }


    @Test
    void previewReturnsOnlyResourceConflictsAndDoesNotSave() throws Exception {
        created(create(other.applicationId(), hrToken, schedule(START, panel, "Room")));
        Map<String, Object> preview = schedule(START, panel, "Room"); preview.put("applicationId", first.applicationId());
        var response = ok(call("POST", "/api/v1/interviews/conflicts", json.writeValueAsString(preview), recruiterToken));
        assertThat(response.size()).isEqualTo(2);
        assertThat(response.toString()).doesNotContain("applicationId", "candidate", "email", "roundName", "meetingUrl", "Finance");
        assertThat(count("interview_sessions")).isEqualTo(1);
    }






    @Test
    void closedApplicationOrPausedRequisitionCannotBeScheduled() throws Exception {
        for (String stage : List.of("WITHDRAWN", "HIRED", "REJECTED")) {
            jdbc.update("UPDATE job_applications SET stage=? WHERE id=?", stage, first.applicationId());
            error(create(first.applicationId(), hrToken, schedule(START, panel, "Room")), 409, "INTERVIEW_APPLICATION_INACTIVE");
        }
        jdbc.update("UPDATE job_applications SET stage='NEW' WHERE id=?", first.applicationId());
        jdbc.update("UPDATE recruitment_requisitions SET status='PAUSED' WHERE id=?", requisition);
        error(create(first.applicationId(), hrToken, schedule(START, panel, "Room")), 409, "INTERVIEW_APPLICATION_INACTIVE");
        assertThat(count("interview_sessions")).isZero();
    }




    @Test
    void pagingIsStableAndLimitedToExactApplication() throws Exception {
        created(create(first.applicationId(), recruiterToken, schedule(START, panel, "Room")));
        created(create(first.applicationId(), recruiterToken, schedule(START.plusSeconds(3600), panel, "Room")));
        created(create(other.applicationId(), hrToken, schedule(START.plusSeconds(7200), panel, "Room")));
        var firstPage = ok(call("GET", collection(first.applicationId()) + "?size=1", null, recruiterToken));
        var secondPage = ok(call("GET", collection(first.applicationId()) + "?size=1&page=1", null, recruiterToken));
        assertThat(firstPage.path("totalElements").asLong()).isEqualTo(2);
        assertThat(firstPage.path("items").size()).isEqualTo(1);
        assertThat(firstPage.path("items").get(0).path("id").asText()).isNotEqualTo(secondPage.path("items").get(0).path("id").asText());
    }

    private Map<String, Object> schedule(Instant start, UUID participant, String room) {
        var body = new LinkedHashMap<String, Object>();
        body.put("roundName", "Vòng chuyên môn"); body.put("startsAt", start.toString()); body.put("durationMinutes", 60);
        body.put("mode", "ONSITE"); if (room != null) body.put("room", room);
        body.put("participantIds", List.of(participant));
        return body;
    }
    private HttpResponse<String> create(UUID application, String token, Map<String, Object> request) throws Exception {
        return call("POST", collection(application), json.writeValueAsString(request), token);
    }
    private void narrowHr(String action, boolean narrow) {
        jdbc.update("UPDATE role_permissions SET permission_code=? WHERE role_code='HR_MANAGER' AND permission_code=?",
                "INTERVIEWS_" + action + (narrow ? "_SCOPED" : "_ALL"),
                "INTERVIEWS_" + action + (narrow ? "_ALL" : "_SCOPED"));
    }
    private String collection(UUID application) { return "/api/v1/applications/" + application + "/interviews"; }
    private JsonNode created(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(v -> assertThat(v).contains("no-store"));
        return json.readTree(response.body());
    }
    private void error(HttpResponse<String> response, int expected, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expected);
        assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo(code);
    }
}
