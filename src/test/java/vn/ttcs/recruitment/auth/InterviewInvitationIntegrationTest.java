package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.auth.passwordreset.ResetTokenGenerator;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Public invitation tokens grant only one appointment's logistics and its final RSVP, never a candidate profile. */
@TestPropertySource(properties = {
        "app.interview-invitation.enabled=true",
        "app.interview-invitation.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.interview-invitation.page-url=http://localhost:8080/api/v1/public/interview-invitations/reply",
        "app.interview-invitation.delivery-enabled=false", "app.interview-invitation.poll-delay-ms=3600000"
})
class InterviewInvitationIntegrationTest extends ScreeningTestSupport {
    private static final String PUBLIC = "/api/v1/public/interview-invitations";
    private static final Instant START = ApplicationTestSupport.NOW.plusSeconds(86400);
    private final ResetTokenGenerator invitationTokens = new ResetTokenGenerator();
    private UUID panel;

    @BeforeEach
    void panelFixture() {
        panel = applications.account("Invitation panel", "invitation-panel@example.test", Role.INTERVIEWER);
    }

    @Test
    void publicLookupContainsOnlyTheInvitedAppointmentAndDoesNotRespond() throws Exception {
        var invite = invitation(first.applicationId(), START, "Java interview");
        var view = lookup(invite.token());
        assertThat(view.propertyNames()).containsExactlyInAnyOrderElementsOf(Set.of("positionTitle", "roundName",
                "startsAt", "endsAt", "mode", "room", "meetingUrl", "preparationInstructions", "status",
                "respondedAt", "expiresAt"));
        assertThat(view.path("roundName").asText()).isEqualTo("Java interview");
        assertThat(view.path("positionTitle").asText()).isNotBlank();
        assertThat(view.path("startsAt").asText()).isEqualTo(START.toString());
        assertThat(view.path("endsAt").asText()).isEqualTo(START.plusSeconds(3600).toString());
        assertThat(view.path("preparationInstructions").asText()).isEqualTo("Mang theo CV và bài tập Java.");
        assertThat(view.path("status").asText()).isEqualTo("PENDING");
        assertThat(view.path("respondedAt").isNull()).isTrue();
        assertThat(view.toString()).doesNotContain("lan@example.test", "candidateId", "applicationId", "salary", "password", invite.token());
        assertThat(status(invite.id())).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT token_hash FROM interview_invitations WHERE id=?", String.class, invite.id()))
                .isEqualTo(invitationTokens.hash(invite.token())).isNotEqualTo(invite.token());
    }

    @Test
    void lookupAndResponseIgnoreAnUnrelatedStaleBearerOnlyOnTheirExactPublicPaths() throws Exception {
        var invite = invitation(first.applicationId(), START, "Public interview");
        ok(call("POST", PUBLIC + "/lookup", json.writeValueAsString(Map.of("token", invite.token())), "expired.invalid.bearer"));
        var response = ok(call("POST", PUBLIC + "/respond", responseBody(invite.token(), "CONFIRM", null), "expired.invalid.bearer"));
        assertThat(response.path("status").asText()).isEqualTo("CONFIRMED");
        for (String path : List.of(PUBLIC + "/lookup/extra", PUBLIC + "/respond/extra")) {
            assertThat(call("POST", path, "{}", "expired.invalid.bearer").statusCode()).isEqualTo(401);
        }
        assertThat(call("GET", PUBLIC + "/lookup", null, null).statusCode()).isEqualTo(401);
        assertThat(call("PUT", PUBLIC + "/respond", "{}", null).statusCode()).isEqualTo(401);
        assertThat(call("GET", "/api/v1/interviews/" + invite.interviewId() + "/invitation", null,
                "expired.invalid.bearer").statusCode()).isEqualTo(401);
    }

    @Test
    void publicReplyPageAndScriptAreNoStoreAndThePageHasNoReferrerAndCsp() throws Exception {
        var page = call("GET", PUBLIC + "/reply", null, null);
        assertThat(page.statusCode()).as(page.body()).isEqualTo(200);
        assertThat(page.headers().firstValue("Content-Type")).hasValueSatisfying(value -> assertThat(value).contains("text/html"));
        assertThat(page.headers().firstValue("Cache-Control")).hasValueSatisfying(value -> assertThat(value).contains("no-store"));
        assertThat(page.headers().firstValue("Referrer-Policy")).hasValue("no-referrer");
        assertThat(page.headers().firstValue("Content-Security-Policy")).hasValueSatisfying(value -> {
            assertThat(value).contains("default-src", "script-src").doesNotContain("unsafe-eval");
            assertThat(java.util.Arrays.stream(value.split(";")).map(String::strip)
                    .filter(directive -> directive.startsWith("script-src")).toList())
                    .allSatisfy(directive -> assertThat(directive).doesNotContain("unsafe-inline"));
        });
        var script = call("GET", PUBLIC + "/reply.js", null, null);
        assertThat(script.statusCode()).as(script.body()).isEqualTo(200);
        assertThat(script.headers().firstValue("Content-Type")).hasValueSatisfying(value -> assertThat(value).contains("javascript"));
        assertThat(script.headers().firstValue("Cache-Control")).hasValueSatisfying(value -> assertThat(value).contains("no-store"));
        assertThat(count("interview_invitations")).isZero();
    }

    @Test
    void confirmationIsFinalAndAnIdenticalRetryKeepsTheOriginalResponseTime() throws Exception {
        var invite = invitation(first.applicationId(), START, "Confirm interview");
        var confirmed = respond(invite.token(), "CONFIRM", null);
        assertThat(confirmed.propertyNames()).containsExactlyInAnyOrder("status", "respondedAt");
        assertThat(confirmed.path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(confirmed.path("respondedAt").asText()).isEqualTo(ApplicationTestSupport.NOW.toString());
        try {
            clock.set(ApplicationTestSupport.NOW.plusSeconds(30));
            assertThat(respond(invite.token(), "CONFIRM", "  ")).isEqualTo(confirmed);
        } finally { clock.set(ApplicationTestSupport.NOW); }
        assertThat(status(invite.id())).isEqualTo("CONFIRMED");
        error(call("POST", PUBLIC + "/respond", responseBody(invite.token(), "REQUEST_RESCHEDULE", "Ngày khác"), null),
                409, "INTERVIEW_INVITATION_ALREADY_RESPONDED");
        assertThat(status(invite.id())).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT response_note FROM interview_invitations WHERE id=?", String.class, invite.id())).isNull();
    }

    @Test
    void onboardingCompletionInvalidatesOldInterviewLookupAndResponse() throws Exception {
        var invite = invitation(first.applicationId(), START, "Obsolete interview");
        JsonNode invalid = invalidLookup(invitationTokens.create());
        applications.stage(first.applicationId(), vn.ttcs.recruitment.application.ApplicationStage.ONBOARDED);
        assertThat(invalidLookup(invite.token())).isEqualTo(invalid);
        var response = call("POST", PUBLIC + "/respond", responseBody(invite.token(), "CONFIRM", null), null);
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json.readTree(response.body())).isEqualTo(invalid);
        assertThat(status(invite.id())).isEqualTo("PENDING");
    }

    @Test
    void confirmationWithAnOptionalNoteIsStoredAndCanBeRetried() throws Exception {
        var invite = invitation(first.applicationId(), START, "Confirmation note interview");
        var confirmed = respond(invite.token(), "CONFIRM", "  Tôi sẽ đến trước giờ hẹn.  ");
        assertThat(confirmed.path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT response_note FROM interview_invitations WHERE id=?", String.class, invite.id()))
                .isEqualTo("Tôi sẽ đến trước giờ hẹn.");
        assertThat(respond(invite.token(), "CONFIRM", "Tôi sẽ đến trước giờ hẹn.")).isEqualTo(confirmed);
        assertThat(ok(call("GET", privatePath(invite.interviewId()), null, recruiterToken)).path("note").asText())
                .isEqualTo("Tôi sẽ đến trước giờ hẹn.");
    }

    @Test
    void rescheduleRequiresReasonAndStoresOnlyTheNormalizedFinalReason() throws Exception {
        var invite = invitation(first.applicationId(), START, "Reschedule interview");
        for (String note : new String[] {null, "", "   ", "\u00a0", "\u200b", "\u200c \u2060", "x".repeat(2001)}) {
            assertThat(call("POST", PUBLIC + "/respond", responseBody(invite.token(), "REQUEST_RESCHEDULE", note), null).statusCode())
                    .isEqualTo(400);
            assertThat(status(invite.id())).isEqualTo("PENDING");
        }
        var responded = respond(invite.token(), "REQUEST_RESCHEDULE", "  Xin đổi sang chiều mai.  ");
        assertThat(responded.path("status").asText()).isEqualTo("RESCHEDULE_REQUESTED");
        assertThat(respond(invite.token(), "REQUEST_RESCHEDULE", "Xin đổi sang chiều mai.")).isEqualTo(responded);
        assertThat(jdbc.queryForObject("SELECT response_note FROM interview_invitations WHERE id=?", String.class, invite.id()))
                .isEqualTo("Xin đổi sang chiều mai.");
        error(call("POST", PUBLIC + "/respond", responseBody(invite.token(), "REQUEST_RESCHEDULE", "Lý do khác"), null),
                409, "INTERVIEW_INVITATION_ALREADY_RESPONDED");
        assertThat(jdbc.queryForObject("SELECT response_note FROM interview_invitations WHERE id=?", String.class, invite.id()))
                .isEqualTo("Xin đổi sang chiều mai.");
        assertThat(jdbc.queryForObject("SELECT starts_at FROM interview_sessions WHERE id=?", Timestamp.class,
                invite.interviewId()).toInstant()).isEqualTo(START);
    }

    @Test
    void windowsAndUnixLineBreaksAreTheSameNormalizedResponseForRetries() throws Exception {
        var invite = invitation(first.applicationId(), START, "Multiline response interview");
        String normalized = "Xin đổi sang chiều mai.\nTôi có thể đến lúc 14 giờ.";
        var response = respond(invite.token(), "REQUEST_RESCHEDULE", "  " + normalized.replace("\n", "\r\n") + "  ");
        assertThat(respond(invite.token(), "REQUEST_RESCHEDULE", normalized)).isEqualTo(response);
        assertThat(jdbc.queryForObject("SELECT response_note FROM interview_invitations WHERE id=?", String.class, invite.id()))
                .isEqualTo(normalized);
    }

    @Test
    void unknownMalformedExpiredAndRevokedTokensHaveTheSameSafeFailure() throws Exception {
        var invite = invitation(first.applicationId(), START, "Invalid token interview");
        JsonNode baseline = invalidLookup(invitationTokens.create());
        assertThat(invalidLookup("short-token")).isEqualTo(baseline);
        assertThat(invalidLookup("!".repeat(43))).isEqualTo(baseline);
        try {
            clock.set(expires(invite.id()));
            assertThat(invalidLookup(invite.token())).isEqualTo(baseline);
            error(call("POST", PUBLIC + "/respond", responseBody(invite.token(), "CONFIRM", null), null),
                    400, "INTERVIEW_INVITATION_INVALID");
        } finally { clock.set(ApplicationTestSupport.NOW); }
        jdbc.update("UPDATE interview_invitations SET revoked_at=? WHERE id=?", Timestamp.from(ApplicationTestSupport.NOW), invite.id());
        assertThat(invalidLookup(invite.token())).isEqualTo(baseline);
        assertThat(status(invite.id())).isEqualTo("PENDING");
    }

    @Test
    void changingTheAppointmentRevokesTheOldTokenAndCreatesANewPendingInvitation() throws Exception {
        var old = invitation(first.applicationId(), START, "Original interview");
        respond(old.token(), "CONFIRM", null);
        var changedBody = schedule(START.plusSeconds(7200), "Changed interview");
        changedBody.put("expectedVersion", 0);
        ok(call("PUT", "/api/v1/interviews/" + old.interviewId(), json.writeValueAsString(changedBody), recruiterToken));
        invalidLookup(old.token());
        error(call("POST", PUBLIC + "/respond", responseBody(old.token(), "CONFIRM", null), null),
                400, "INTERVIEW_INVITATION_INVALID");
        var fresh = tokenFor(old.interviewId(), 1);
        assertThat(lookup(fresh.token()).path("roundName").asText()).isEqualTo("Changed interview");
        assertThat(lookup(fresh.token()).path("startsAt").asText()).isEqualTo(START.plusSeconds(7200).toString());
        assertThat(status(fresh.id())).isEqualTo("PENDING");
        assertThat(status(old.id())).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT revoked_at IS NOT NULL FROM interview_invitations WHERE id=?", Boolean.class, old.id())).isTrue();
        assertThat(count("interview_invitations")).isEqualTo(2);
    }

    @Test
    void cancellationInvalidatesTheLinkWithoutCreatingAnotherCandidateInvitation() throws Exception {
        var invite = invitation(first.applicationId(), START, "Cancelled interview");
        ok(call("POST", "/api/v1/interviews/" + invite.interviewId() + "/cancel",
                "{\"expectedVersion\":0,\"reason\":\"Đổi kế hoạch tuyển dụng\"}", recruiterToken));
        invalidLookup(invite.token());
        error(call("POST", PUBLIC + "/respond", responseBody(invite.token(), "CONFIRM", null), null),
                400, "INTERVIEW_INVITATION_INVALID");
        assertThat(count("interview_invitations")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT revoked_at IS NOT NULL FROM interview_invitations WHERE id=?", Boolean.class, invite.id())).isTrue();
        assertThat(status(invite.id())).isEqualTo("PENDING");
    }

    @Test
    void aTokenForOneApplicationDoesNotExposeAnotherApplicationOfTheSameCandidate() throws Exception {
        var a = invitation(first.applicationId(), START, "Java interview");
        var b = invitation(other.applicationId(), START.plusSeconds(7200), "Finance interview");
        assertThat(first.candidateId()).isEqualTo(other.candidateId());
        assertThat(lookup(a.token()).path("roundName").asText()).isEqualTo("Java interview");
        assertThat(lookup(b.token()).path("roundName").asText()).isEqualTo("Finance interview");
        respond(a.token(), "CONFIRM", null);
        assertThat(status(a.id())).isEqualTo("CONFIRMED");
        assertThat(status(b.id())).isEqualTo("PENDING");
        var injected = Map.of("token", a.token(), "interviewId", b.interviewId(), "action", "CONFIRM");
        assertThat(call("POST", PUBLIC + "/respond", json.writeValueAsString(injected), null).statusCode()).isEqualTo(400);
        assertThat(status(b.id())).isEqualTo("PENDING");
        error(call("GET", privatePath(b.interviewId()), null, recruiterToken), 403, "FORBIDDEN");
    }

    @Test
    void onlyAScopedCandidateReaderCanReadThePrivateRsvpReason() throws Exception {
        var invite = invitation(first.applicationId(), START, "Private RSVP interview");
        respond(invite.token(), "REQUEST_RESCHEDULE", "Có việc gia đình cần đổi giờ.");
        var status = ok(call("GET", privatePath(invite.interviewId()), null, recruiterToken));
        assertThat(status.propertyNames()).containsExactlyInAnyOrder("status", "respondedAt", "note", "expiresAt");
        assertThat(status.path("status").asText()).isEqualTo("RESCHEDULE_REQUESTED");
        assertThat(status.path("note").asText()).isEqualTo("Có việc gia đình cần đổi giờ.");
        assertThat(status.toString()).doesNotContain(invite.token(), "tokenHash", "token_hash", "lan@example.test");
        ok(call("GET", privatePath(invite.interviewId()), null, token(manager)));
        ok(call("GET", "/api/v1/interviews/" + invite.interviewId(), null, token(panel)));
        error(call("GET", privatePath(invite.interviewId()), null, token(panel)), 403, "FORBIDDEN");
        error(call("GET", privatePath(invite.interviewId()), null, token(outsider)), 403, "FORBIDDEN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_access_events WHERE actor_id=? AND outcome='DENIED'",
                Long.class, outsider)).isEqualTo(1);
    }

    @Test
    void privateStatusIntersectionCannotBeWidenedByCandidateReadAll() throws Exception {
        var invite = invitation(first.applicationId(), START, "Scoped RSVP interview");
        jdbc.update("UPDATE role_permissions SET permission_code='INTERVIEWS_READ_SCOPED' "
                + "WHERE role_code='HR_MANAGER' AND permission_code='INTERVIEWS_READ_ALL'");
        try {
            error(call("GET", privatePath(invite.interviewId()), null, hrToken), 403, "FORBIDDEN");
            applications.assign(requisition, hr, "SUPPORTING", hr);
            ok(call("GET", privatePath(invite.interviewId()), null, hrToken));
        } finally {
            jdbc.update("UPDATE role_permissions SET permission_code='INTERVIEWS_READ_ALL' "
                    + "WHERE role_code='HR_MANAGER' AND permission_code='INTERVIEWS_READ_SCOPED'");
        }
    }

    @Test
    void appointmentWithoutAnInvitationReturnsNotSentToItsAuthorizedReader() throws Exception {
        UUID interview = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO interview_sessions(id,application_id,round_name,starts_at,ends_at,mode,room,
                    normalized_room,created_by,created_at,updated_at)
                VALUES (?,?,'Legacy interview',?,?,'ONSITE','Room','room',?,?,?)
                """, interview, first.applicationId(), Timestamp.from(START), Timestamp.from(START.plusSeconds(3600)),
                hr, Timestamp.from(ApplicationTestSupport.NOW), Timestamp.from(ApplicationTestSupport.NOW));
        var state = ok(call("GET", privatePath(interview), null, recruiterToken));
        assertThat(state.path("status").asText()).isEqualTo("NOT_SENT");
        assertThat(state.path("respondedAt").isNull()).isTrue();
        assertThat(state.path("note").isNull()).isTrue();
        assertThat(state.path("expiresAt").isNull()).isTrue();
        assertThat(count("interview_invitations")).isZero();
    }

    @Test
    void invalidActionsOrUnexpectedFieldsNeverMutateTheRsvp() throws Exception {
        var invite = invitation(first.applicationId(), START, "Input validation interview");
        for (String action : List.of("CANCEL", "confirmed", "", "CONFIRM ")) {
            assertThat(call("POST", PUBLIC + "/respond", responseBody(invite.token(), action, null), null).statusCode())
                    .isEqualTo(400);
        }
        var extra = Map.of("token", invite.token(), "action", "CONFIRM", "status", "CONFIRMED");
        assertThat(call("POST", PUBLIC + "/respond", json.writeValueAsString(extra), null).statusCode()).isEqualTo(400);
        assertThat(status(invite.id())).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT responded_at FROM interview_invitations WHERE id=?", Timestamp.class, invite.id())).isNull();
    }

    @Test
    void twoConcurrentDifferentResponsesHaveExactlyOneWinner() throws Exception {
        var invite = invitation(first.applicationId(), START, "Concurrent RSVP interview");
        lookup(invite.token());
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var confirm = executor.submit(() -> { barrier.await(10, TimeUnit.SECONDS);
                return call("POST", PUBLIC + "/respond", responseBody(invite.token(), "CONFIRM", null), null); });
            var change = executor.submit(() -> { barrier.await(10, TimeUnit.SECONDS);
                return call("POST", PUBLIC + "/respond", responseBody(invite.token(), "REQUEST_RESCHEDULE", "Xin đổi ngày."), null); });
            var a = confirm.get(20, TimeUnit.SECONDS); var b = change.get(20, TimeUnit.SECONDS);
            assertThat(List.of(a.statusCode(), b.statusCode())).containsExactlyInAnyOrder(200, 409);
            var winner = json.readTree(a.statusCode() == 200 ? a.body() : b.body());
            assertThat(status(invite.id())).isEqualTo(winner.path("status").asText());
            String note = jdbc.queryForObject("SELECT response_note FROM interview_invitations WHERE id=?", String.class, invite.id());
            if (a.statusCode() == 200) assertThat(note).isNull();
            else assertThat(note).isEqualTo("Xin đổi ngày.");
        }
    }

    @Test
    void expiryDuringAnInterviewLockWaitDoesNotAllowALateResponse() throws Exception {
        var invite = invitation(first.applicationId(), START, "Expiring RSVP interview");
        lookup(invite.token());
        try (Connection blocker = dataSource.getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try (var lock = blocker.prepareStatement("SELECT id FROM interview_sessions WHERE id=? FOR UPDATE")) {
                lock.setObject(1, invite.interviewId()); lock.executeQuery().close();
            }
            var pending = executor.submit(() -> call("POST", PUBLIC + "/respond", responseBody(invite.token(), "CONFIRM", null), null));
            try {
                applications.awaitWaiterOf(ApplicationTestSupport.backendPid(blocker));
                clock.set(expires(invite.id()));
            } finally { blocker.rollback(); }
            error(pending.get(10, TimeUnit.SECONDS), 400, "INTERVIEW_INVITATION_INVALID");
        } finally { clock.set(ApplicationTestSupport.NOW); }
        assertThat(status(invite.id())).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT responded_at FROM interview_invitations WHERE id=?", Timestamp.class, invite.id())).isNull();
    }

    private record Invitation(UUID id, UUID interviewId, String token) { }

    private Invitation invitation(UUID application, Instant startsAt, String round) throws Exception {
        var response = call("POST", "/api/v1/applications/" + application + "/interviews",
                json.writeValueAsString(schedule(startsAt, round)), hrToken);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return tokenFor(UUID.fromString(json.readTree(response.body()).path("id").asText()), 0);
    }

    private Invitation tokenFor(UUID interview, long version) {
        UUID id = jdbc.queryForObject("SELECT id FROM interview_invitations WHERE interview_id=? AND schedule_version=?",
                UUID.class, interview, version);
        String token = invitationTokens.create();
        assertThat(jdbc.update("UPDATE interview_invitations SET token_hash=? WHERE id=?", invitationTokens.hash(token), id)).isEqualTo(1);
        return new Invitation(id, interview, token);
    }

    private Map<String, Object> schedule(Instant start, String round) {
        var body = new LinkedHashMap<String, Object>();
        body.put("roundName", round); body.put("startsAt", start.toString()); body.put("durationMinutes", 60);
        body.put("mode", "ONSITE"); body.put("room", "Invitation room"); body.put("participantIds", List.of(panel));
        body.put("preparationInstructions", "Mang theo CV và bài tập Java.");
        return body;
    }

    private JsonNode lookup(String token) throws Exception {
        return ok(call("POST", PUBLIC + "/lookup", json.writeValueAsString(Map.of("token", token)), null));
    }

    private JsonNode invalidLookup(String token) throws Exception {
        var response = call("POST", PUBLIC + "/lookup", json.writeValueAsString(Map.of("token", token)), null);
        error(response, 400, "INTERVIEW_INVITATION_INVALID");
        assertThat(response.body()).doesNotContain(token, "lan@example.test");
        return json.readTree(response.body());
    }

    private JsonNode respond(String token, String action, String note) throws Exception {
        return ok(call("POST", PUBLIC + "/respond", responseBody(token, action, note), null));
    }

    private String responseBody(String token, String action, String note) {
        var body = new LinkedHashMap<String, Object>();
        body.put("token", token); body.put("action", action); body.put("note", note);
        return json.writeValueAsString(body);
    }

    private String status(UUID invitation) {
        return jdbc.queryForObject("SELECT status FROM interview_invitations WHERE id=?", String.class, invitation);
    }

    private Instant expires(UUID invitation) {
        return jdbc.queryForObject("SELECT expires_at FROM interview_invitations WHERE id=?", Timestamp.class, invitation).toInstant();
    }

    private static String privatePath(UUID interview) { return "/api/v1/interviews/" + interview + "/invitation"; }

    private void error(HttpResponse<String> response, int expected, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expected);
        assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(value -> assertThat(value).contains("no-store"));
        assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo(code);
    }
}
