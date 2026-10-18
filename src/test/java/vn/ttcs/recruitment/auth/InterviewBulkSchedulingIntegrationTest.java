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

    @Test
    void confirmCreatesEveryConsecutiveSlotAndItsOwnPreparationInstructions() throws Exception {
        var request = body(List.of(other.applicationId(), first.applicationId()));
        request.put("preparationInstructions", "Mang theo CV và bài tập Java.");
        var result = created(post("", request, hrToken));
        assertThat(result.path("requestId").asText()).isEqualTo(request.get("requestId").toString());
        var items = result.path("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).path("applicationId").asText()).isEqualTo(other.applicationId().toString());
        assertThat(items.get(1).path("applicationId").asText()).isEqualTo(first.applicationId().toString());
        assertThat(items.get(0).path("endsAt").asText()).isEqualTo(items.get(1).path("startsAt").asText());
        for (JsonNode item : items) {
            assertThat(item.path("preparationInstructions").asText()).isEqualTo("Mang theo CV và bài tập Java.");
            assertThat(item.path("version").asLong()).isZero();
            assertThat(item.path("participantIds").get(0).asText()).isEqualTo(panel.toString());
        }
        assertThat(count("interview_sessions")).isEqualTo(2);
        assertThat(count("interview_events")).isEqualTo(2);
        assertThat(count("interview_bulk_requests")).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT stage FROM job_applications ORDER BY id", String.class)).containsOnly("NEW");
    }

    @Test
    void validatesDistinctApplicationsWindowCapacityAndMaximumDayBeforeWriting() throws Exception {
        var valid = body(List.of(first.applicationId(), other.applicationId()));
        var tooMany = java.util.stream.IntStream.range(0, 51).mapToObj(n -> UUID.randomUUID()).toList();
        for (Map<String, Object> invalid : List.of(
                Map.<String, Object>of("applicationIds", List.of()),
                Map.<String, Object>of("applicationIds", List.of(first.applicationId(), first.applicationId())),
                Map.<String, Object>of("applicationIds", tooMany),
                Map.<String, Object>of("windowEnd", START.plusSeconds(7199).toString()),
                Map.<String, Object>of("windowEnd", START.toString()),
                Map.<String, Object>of("windowEnd", START.plusSeconds(86401).toString()),
                Map.<String, Object>of("durationMinutes", 0),
                Map.<String, Object>of("windowStart", ApplicationTestSupport.NOW.toString(),
                        "windowEnd", ApplicationTestSupport.NOW.plusSeconds(7200).toString()))) {
            var request = new LinkedHashMap<>(valid); request.putAll(invalid);
            error(post("", request, hrToken), 400, "VALIDATION_ERROR");
        }
        valid.remove("requestId");
        error(post("", valid, hrToken), 400, "VALIDATION_ERROR");
        assertNothingScheduled();
    }

    @Test
    void oneLateConflictIsVisibleInPreviewAndRollsBackTheEntireConfirmation() throws Exception {
        single(START.plusSeconds(3600));
        var request = body(List.of(first.applicationId(), other.applicationId()));
        var preview = ok(post("/preview", request, hrToken));
        assertThat(preview.path("canCreate").asBoolean()).isFalse();
        assertThat(preview.path("slots").get(0).path("conflicts")).isEmpty();
        assertThat(preview.path("slots").get(1).path("conflicts")).hasSize(2);
        error(post("", request, hrToken), 409, "INTERVIEW_CONFLICT");
        assertThat(count("interview_sessions")).isEqualTo(1);
        assertThat(count("interview_events")).isEqualTo(1);
        assertThat(count("interview_bulk_requests")).isZero();
    }

    @Test
    void aConflictCreatedAfterPreviewIsCheckedAgainOnConfirmation() throws Exception {
        var request = body(List.of(first.applicationId(), other.applicationId()));
        assertThat(ok(post("/preview", request, hrToken)).path("canCreate").asBoolean()).isTrue();
        single(START.plusSeconds(3600));
        error(post("", request, hrToken), 409, "INTERVIEW_CONFLICT");
        assertThat(count("interview_sessions")).isEqualTo(1);
        assertThat(count("interview_bulk_requests")).isZero();
    }

    @Test
    void oneOutsideScopeOrUnknownApplicationCannotPartiallyScheduleTheAccessibleApplication() throws Exception {
        error(post("", body(List.of(first.applicationId(), other.applicationId())), recruiterToken), 403, "FORBIDDEN");
        error(post("/preview", body(List.of(first.applicationId(), other.applicationId())), recruiterToken), 403, "FORBIDDEN");
        error(post("", body(List.of(first.applicationId(), UUID.randomUUID())), hrToken), 404, "APPLICATION_NOT_FOUND");
        assertNothingScheduled();
        assertThat(jdbc.queryForList("SELECT outcome FROM candidate_access_events WHERE application_id=?",
                String.class, other.applicationId())).containsOnly("DENIED");
    }

    @Test
    void oneInactiveApplicationRejectsTheWholeBatch() throws Exception {
        jdbc.update("UPDATE job_applications SET stage='WITHDRAWN' WHERE id=?", other.applicationId());
        error(post("", body(List.of(first.applicationId(), other.applicationId())), hrToken), 409, "INTERVIEW_APPLICATION_INACTIVE");
        assertNothingScheduled();
    }

    @Test
    void onlyRecruiterMayConfirmConflictsAndEachOverriddenSlotHasTheReason() throws Exception {
        single(START.plusSeconds(3600));
        var request = body(List.of(first.applicationId(), other.applicationId()));
        request.put("overrideConflicts", true);
        error(post("", request, recruiterToken), 400, "VALIDATION_ERROR");
        request.put("overrideReason", "Đã thống nhất đổi ca với người tham gia.");
        error(post("/preview", request, hrToken), 403, "FORBIDDEN");
        applications.assign(otherRequisition, recruiter, "SUPPORTING", hr);
        assertThat(ok(post("/preview", request, recruiterToken)).path("canCreate").asBoolean()).isTrue();
        var result = created(post("", request, recruiterToken));
        UUID firstId = UUID.fromString(result.path("items").get(0).path("id").asText());
        UUID secondId = UUID.fromString(result.path("items").get(1).path("id").asText());
        assertThat(jdbc.queryForObject("SELECT conflict_override FROM interview_events WHERE interview_id=?", Boolean.class, firstId)).isFalse();
        assertThat(jdbc.queryForObject("SELECT conflict_override FROM interview_events WHERE interview_id=?", Boolean.class, secondId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT override_reason FROM interview_events WHERE interview_id=?", String.class, secondId))
                .isEqualTo(request.get("overrideReason"));
    }

    @Test
    void identicalRetryReturnsSavedResponseWhileChangedPayloadOrOrderIsRejected() throws Exception {
        var request = body(List.of(first.applicationId(), other.applicationId()));
        var firstResponse = created(post("", request, hrToken));
        assertThat(created(post("", request, hrToken))).isEqualTo(firstResponse);
        request.put("room", "Different room");
        error(post("", request, hrToken), 409, "INTERVIEW_BULK_REQUEST_CHANGED");
        request.put("room", "Bulk room");
        request.put("applicationIds", List.of(other.applicationId(), first.applicationId()));
        error(post("", request, hrToken), 409, "INTERVIEW_BULK_REQUEST_CHANGED");
        assertThat(count("interview_sessions")).isEqualTo(2);
        assertThat(count("interview_events")).isEqualTo(2);
        assertThat(count("interview_bulk_requests")).isEqualTo(1);
    }

    @Test
    void successfulRetryStillWorksAfterTheOriginalSlotPassesOrApplicationStops() throws Exception {
        var request = body(List.of(first.applicationId(), other.applicationId()));
        var firstResponse = created(post("", request, hrToken));
        jdbc.update("UPDATE job_applications SET stage='WITHDRAWN' WHERE id=?", first.applicationId());
        jdbc.update("UPDATE user_accounts SET enabled=false WHERE id=?", panel);
        try {
            clock.set(START.plusSeconds(86400));
            assertThat(created(post("", request, token(hr)))).isEqualTo(firstResponse);
        } finally { clock.set(ApplicationTestSupport.NOW); }
        assertThat(count("interview_sessions")).isEqualTo(2);
        assertThat(count("interview_bulk_requests")).isEqualTo(1);
    }

    @Test
    void successfulRetryStillRequiresCurrentApplicationScope() throws Exception {
        applications.assign(otherRequisition, recruiter, "SUPPORTING", hr);
        var request = body(List.of(first.applicationId(), other.applicationId()));
        created(post("", request, recruiterToken));
        jdbc.update("DELETE FROM requisition_recruiters WHERE requisition_id=? AND recruiter_id=?",
                otherRequisition, recruiter);
        error(post("", request, recruiterToken), 403, "FORBIDDEN");
        assertThat(count("interview_sessions")).isEqualTo(2);
        assertThat(count("interview_bulk_requests")).isEqualTo(1);
    }

    @Test
    void simultaneousRetriesCreateExactlyOneBatch() throws Exception {
        var request = body(List.of(first.applicationId(), other.applicationId()));
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return post("", request, hrToken); });
            var b = executor.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return post("", request, hrToken); });
            assertThat(created(a.get(20, TimeUnit.SECONDS))).isEqualTo(created(b.get(20, TimeUnit.SECONDS)));
        }
        assertThat(count("interview_sessions")).isEqualTo(2);
        assertThat(count("interview_events")).isEqualTo(2);
        assertThat(count("interview_bulk_requests")).isEqualTo(1);
    }

    @Test
    void concurrentOppositeOrderBatchesCannotSaveHalfOrDeadlock() throws Exception {
        applications.assign(otherRequisition, recruiter, "SUPPORTING", hr);
        var firstRequest = body(List.of(first.applicationId(), other.applicationId()));
        var secondRequest = body(List.of(other.applicationId(), first.applicationId()));
        UUID differentPanel = applications.account("Other bulk interviewer", "bulk-other@example.test", Role.INTERVIEWER);
        secondRequest.put("participantIds", List.of(differentPanel));
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return post("", firstRequest, hrToken); });
            var b = executor.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return post("", secondRequest, recruiterToken); });
            var responses = List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
            assertThat(responses.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(201, 409);
        }
        assertThat(count("interview_sessions")).isEqualTo(2);
        assertThat(count("interview_events")).isEqualTo(2);
        assertThat(count("interview_bulk_requests")).isEqualTo(1);
    }

    @Test
    void removedWriteGrantDuringResourceWaitPreventsEverySlotFromBeingCreated() throws Exception {
        var request = body(List.of(first.applicationId(), other.applicationId()));
        ok(call("GET", "/api/v1/interviews/me", null, hrToken));
        try (Connection blocker = dataSource.getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.createStatement()) { statement.execute("SELECT pg_advisory_xact_lock(743882,1)"); }
            var pending = executor.submit(() -> post("", request, hrToken));
            try {
                applications.awaitWaiterOf(ApplicationTestSupport.backendPid(blocker));
                assertThat(jdbc.update("DELETE FROM role_permissions WHERE role_code='HR_MANAGER' AND permission_code='INTERVIEWS_WRITE_ALL'"))
                        .isEqualTo(1);
            } finally { blocker.rollback(); }
            error(pending.get(10, TimeUnit.SECONDS), 403, "FORBIDDEN");
        } finally {
            jdbc.update("INSERT INTO role_permissions(role_code,permission_code) VALUES ('HR_MANAGER','INTERVIEWS_WRITE_ALL') ON CONFLICT DO NOTHING");
        }
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
