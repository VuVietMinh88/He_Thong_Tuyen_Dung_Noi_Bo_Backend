package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Actual HTTP/PostgreSQL checks of the invitation-specific preparation boundary, with synthetic fixtures only. */
class PersonalInterviewCalendarIntegrationTest extends InterviewEvaluationTestSupport {
    private static final String CALENDAR = "/api/v1/interviews/me/calendar";
    private static final Instant NOW = ApplicationTestSupport.NOW;

    @Test
    void defaultCalendarShowsUpcomingAndOngoingInvitedSlotsInAscendingOrderWithMinimalCandidateData() throws Exception {
        UUID future = slot(first.applicationId(), NOW.plusSeconds(7200), panel);
        UUID ongoing = slot(first.applicationId(), NOW.minusSeconds(1800), panel);
        slot(other.applicationId(), NOW.plusSeconds(3600), peer);
        UUID removed = slot(first.applicationId(), NOW.plusSeconds(10800), panel);
        jdbc.update("UPDATE interview_participants SET active=false WHERE interview_id=?", removed);
        UUID cancelled = slot(first.applicationId(), NOW.plusSeconds(14400), panel);
        jdbc.update("UPDATE interview_sessions SET status='CANCELLED' WHERE id=?", cancelled);
        slot(first.applicationId(), NOW.plus(31, ChronoUnit.DAYS), panel);
        JsonNode result = calendar("", panelToken);
        assertThat(result.path("totalElements").asLong()).isEqualTo(2);
        assertThat(ids(result)).containsExactly(ongoing.toString(), future.toString());
        JsonNode firstItem = result.path("items").get(0);
        assertThat(firstItem.path("candidateName").asText()).isEqualTo(ApplicationTestSupport.FULL_NAME);
        assertThat(firstItem.path("positionId").asText()).isEqualTo(position.toString());
        assertThat(firstItem.path("positionName").asText()).isEqualTo(jdbc.queryForObject("SELECT name FROM positions WHERE id=?", String.class, position));
        assertThat(firstItem.path("evaluationState").asText()).isEqualTo("NOT_STARTED");
        assertThat(firstItem.path("evaluationSubmitted").asBoolean()).isFalse();
        assertThat(firstItem.path("needsSubmission").asBoolean()).isFalse();
        assertThat(firstItem.path("cvAvailable").asBoolean()).isTrue();
        assertThat(firstItem.path("cvUrl").asText()).isEqualTo(cvPath(ongoing));
        assertThat(result.toString()).doesNotContain("email", "phone", "salary", "tracking", "conclusion", "score", "comment", "candidateId");
        assertThat(count("evaluation_forms")).isZero();
    }

    @Test
    void candidateDisclosureAuditsOnlyApplicationsActuallyOnPageOncePerApplication() throws Exception {
        slot(first.applicationId(), NOW.plusSeconds(3600), panel);
        slot(first.applicationId(), NOW.plusSeconds(7200), panel);
        slot(other.applicationId(), NOW.plusSeconds(10800), panel);
        calendar("?size=2", panelToken);
        assertThat(jdbc.queryForList("SELECT application_id FROM candidate_access_events WHERE actor_id=? AND outcome='GRANTED' AND access_kind='PROFILE'",
                UUID.class, panel)).containsExactly(first.applicationId());
        assertThat(jdbc.queryForObject("SELECT access_scope FROM candidate_access_events WHERE actor_id=?", String.class, panel)).isEqualTo("SCOPED");
    }

    @Test
    void eachCallerSeesOnlyOwnActiveInvitationsEvenWhenHoldingAllGrants() throws Exception {
        UUID firstSlot = slot(first.applicationId(), NOW.plusSeconds(3600), panel);
        UUID otherSlot = slot(other.applicationId(), NOW.plusSeconds(3600), peer);
        assertThat(ids(calendar("", panelToken))).containsExactly(firstSlot.toString());
        assertThat(ids(calendar("", peerToken))).containsExactly(otherSlot.toString());
        // HR has global grants, but this endpoint is still their personal calendar.
        assertThat(calendar("", hrToken).path("totalElements").asLong()).isZero();
    }

    @Test
    void pendingListTracksOnlyOwnFormThroughDraftSubmissionAndReopening() throws Exception {
        assertThat(item(calendar("?includePending=true", panelToken), interview).path("evaluationState").asText()).isEqualTo("NOT_STARTED");
        assertThat(item(calendar("?includePending=true", panelToken), interview).path("needsSubmission").asBoolean()).isTrue();
        UUID formId = UUID.fromString(form(panelToken).path("id").asText());
        form(peerToken);
        ok(call("POST", ownPath() + "/submit", json.writeValueAsString(payload(0, 5, 5, "HIRE")), peerToken));
        assertThat(item(calendar("?includePending=true", panelToken), interview).path("evaluationState").asText()).isEqualTo("DRAFT");
        ok(call("POST", ownPath() + "/submit", json.writeValueAsString(payload(0, 3, 4, "CONSIDER")), panelToken));
        assertThat(calendar("?includePending=true", panelToken).path("totalElements").asLong()).isZero();
        JsonNode completed = item(calendar(history(), panelToken), interview);
        assertThat(completed.path("evaluationState").asText()).isEqualTo("SUBMITTED");
        assertThat(completed.path("evaluationSubmitted").asBoolean()).isTrue();
        assertThat(completed.path("needsSubmission").asBoolean()).isFalse();
        ok(call("POST", reopenPath(formId), "{\"expectedVersion\":1,\"reason\":\"Bổ sung nhận xét\"}", recruiterToken));
        JsonNode reopened = item(calendar("?includePending=true", panelToken), interview);
        assertThat(reopened.path("evaluationState").asText()).isEqualTo("REOPENED");
        assertThat(reopened.path("evaluationSubmitted").asBoolean()).isFalse();
        assertThat(reopened.path("needsSubmission").asBoolean()).isTrue();
        assertThat(reopened.toString()).doesNotContain("Technical note", "HIRE", "CONSIDER", "totalScore");
    }

    @Test
    void needsSubmissionStartsExactlyAtInterviewEndAndNotDuringAppointment() throws Exception {
        jdbc.update("UPDATE interview_sessions SET starts_at=?,ends_at=? WHERE id=?",
                Timestamp.from(NOW.minusSeconds(3600)), Timestamp.from(NOW.plusSeconds(1)), interview);
        assertThat(item(calendar("", panelToken), interview).path("needsSubmission").asBoolean()).isFalse();
        jdbc.update("UPDATE interview_sessions SET ends_at=? WHERE id=?", Timestamp.from(NOW), interview);
        assertThat(calendar("", panelToken).path("totalElements").asLong()).isZero();
        assertThat(item(calendar("?includePending=true", panelToken), interview).path("needsSubmission").asBoolean()).isTrue();
    }

    @Test
    void pendingDefaultLookbackIsBoundedAndExplicitRangeCanRecoverOlderUnfinishedInterview() throws Exception {
        UUID old = slot(first.applicationId(), NOW.minus(91, ChronoUnit.DAYS), panel);
        assertThat(ids(calendar("?includePending=true", panelToken))).doesNotContain(old.toString());
        String range = "?from=" + NOW.minus(92, ChronoUnit.DAYS) + "&to=" + NOW.minus(90, ChronoUnit.DAYS);
        assertThat(ids(calendar(range, panelToken))).containsExactly(old.toString());
    }

    @Test
    void explicitWindowUsesHalfOpenOverlapAndStablePagination() throws Exception {
        UUID left = slot(first.applicationId(), NOW.plusSeconds(3600), panel);
        UUID firstSame = slot(first.applicationId(), NOW.plusSeconds(7200), panel);
        UUID secondSame = slot(first.applicationId(), NOW.plusSeconds(7200), panel);
        UUID right = slot(first.applicationId(), NOW.plusSeconds(10800), panel);
        String window = "?from=" + NOW.plusSeconds(7200) + "&to=" + NOW.plusSeconds(10800);
        var full = calendar(window, panelToken);
        assertThat(ids(full)).containsExactlyInAnyOrder(firstSame.toString(), secondSame.toString()).doesNotContain(left.toString(), right.toString());
        assertThat(full.path("totalElements").asLong()).isEqualTo(2);
        var firstPage = calendar(window + "&size=1", panelToken);
        var secondPage = calendar(window + "&size=1&page=1", panelToken);
        assertThat(firstPage.path("totalElements").asLong()).isEqualTo(2);
        assertThat(ids(firstPage).get(0)).isEqualTo(ids(full).get(0));
        assertThat(ids(secondPage).get(0)).isEqualTo(ids(full).get(1));
        assertThat(calendar(window + "&page=3&size=1", panelToken).path("items").size()).isZero();
    }

    @Test
    void timeAndPagingValidationRejectsUnboundedReversedOrMalformedValues() throws Exception {
        for (String query : List.of("?page=-1", "?page=1000001", "?size=0", "?size=101", "?from=not-time",
                "?from=" + NOW + "&to=" + NOW, "?from=" + NOW.plusSeconds(1) + "&to=" + NOW,
                "?from=" + NOW + "&to=" + NOW.plus(367, ChronoUnit.DAYS))) {
            error(call("GET", CALENDAR + query, null, panelToken), 400, "VALIDATION_ERROR");
        }
        assertThat(count("candidate_access_events")).isZero();
    }

    @Test
    void invitationSpecificCvWorksWithoutGlobalCandidateAccessAndKeepsOtherApplicationPrivate() throws Exception {
        byte[] expected = jdbc.queryForObject("SELECT content FROM application_cvs WHERE application_id=?", byte[].class, first.applicationId());
        HttpResponse<byte[]> response = cv(interview, panelToken);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(expected);
        assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(value -> assertThat(value).contains("no-store"));
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/pdf");
        assertThat(response.headers().firstValue("Content-Disposition")).hasValueSatisfying(value -> assertThat(value).startsWith("inline;").contains("filename*=UTF-8''"));
        assertThat(response.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
        assertThat(response.headers().firstValue("Content-Security-Policy")).hasValue("default-src 'none'; sandbox");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_access_events WHERE actor_id=? AND application_id=? AND access_kind='CV' AND outcome='GRANTED'",
                Long.class, panel, first.applicationId())).isEqualTo(1);
        error(call("GET", "/api/v1/applications/" + first.applicationId() + "/cv", null, panelToken), 403, "FORBIDDEN");
        error(call("GET", "/api/v1/candidates/" + first.candidateId(), null, panelToken), 403, "FORBIDDEN");
        UUID otherSlot = slot(other.applicationId(), NOW.plusSeconds(3600), peer);
        error(call("GET", cvPath(otherSlot), null, panelToken), 403, "FORBIDDEN");
    }

    @Test
    void removingCandidateReadPermissionDoesNotDisableInvitationContextButGlobalCvRemainsDenied() throws Exception {
        // This permission may already be absent in some historical seed paths.
        int removed = jdbc.update("DELETE FROM role_permissions WHERE role_code='INTERVIEWER' AND permission_code='CANDIDATES_READ_SCOPED'");
        try {
            assertThat(cv(interview, panelToken).statusCode()).isEqualTo(200);
            assertThat(calendar("?includePending=true", panelToken).path("totalElements").asLong()).isEqualTo(1);
            error(call("GET", "/api/v1/applications/" + first.applicationId() + "/cv", null, panelToken), 403, "FORBIDDEN");
        } finally {
            if (removed != 0) jdbc.update("INSERT INTO role_permissions(role_code,permission_code) VALUES ('INTERVIEWER','CANDIDATES_READ_SCOPED')");
        }
    }

    @Test
    void removedOrCancelledPanelCannotReadCvAndDenialsAreAuditedAgainstExactApplication() throws Exception {
        jdbc.update("UPDATE interview_participants SET active=false WHERE interview_id=? AND user_id=?", interview, panel);
        error(call("GET", cvPath(interview), null, panelToken), 403, "FORBIDDEN");
        assertThat(calendar("?includePending=true", panelToken).path("totalElements").asLong()).isZero();
        jdbc.update("UPDATE interview_participants SET active=true WHERE interview_id=? AND user_id=?", interview, panel);
        jdbc.update("UPDATE interview_sessions SET status='CANCELLED' WHERE id=?", interview);
        error(call("GET", cvPath(interview), null, panelToken), 403, "FORBIDDEN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_access_events WHERE actor_id=? AND application_id=? AND access_kind='CV' AND outcome='DENIED'",
                Long.class, panel, first.applicationId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_access_events WHERE actor_id=? AND outcome='GRANTED'", Long.class, panel)).isZero();
    }

    @Test
    void globalHrGrantsNeverSubstituteForInvitationOnInterviewCv() throws Exception {
        error(call("GET", cvPath(interview), null, hrToken), 403, "FORBIDDEN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_access_events WHERE actor_id=? AND application_id=? AND outcome='DENIED'",
                Long.class, hr, first.applicationId())).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"WITHDRAWN", "REJECTED", "HIRED", "OFFER"})
    void inactiveApplicationRevokesCalendarAndCvDespitePreservedInvitation(String stage) throws Exception {
        UUID future = slot(first.applicationId(), NOW.plusSeconds(3600), panel);
        jdbc.update("UPDATE job_applications SET stage=? WHERE id=?", stage, first.applicationId());
        assertInactiveParentHidesPreparation(future);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PAUSED", "FILLED", "CANCELLED"})
    void inactiveRequisitionRevokesCalendarAndCvDespitePreservedInvitation(String status) throws Exception {
        UUID future = slot(first.applicationId(), NOW.plusSeconds(3600), panel);
        jdbc.update("UPDATE recruitment_requisitions SET status=? WHERE id=?", status, requisition);
        assertInactiveParentHidesPreparation(future);
    }

    @Test
    void absentCvHasNoCapabilityAndNoBytesOrGrantedCvAudit() throws Exception {
        jdbc.update("DELETE FROM application_cvs WHERE application_id=?", first.applicationId());
        JsonNode row = item(calendar("?includePending=true", panelToken), interview);
        assertThat(row.path("cvAvailable").asBoolean()).isFalse();
        assertThat(row.path("cvUrl").isNull()).isTrue();
        error(call("GET", cvPath(interview), null, panelToken), 404, "APPLICATION_CV_NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_access_events WHERE access_kind='CV'", Long.class)).isZero();
    }

    @Test
    void docxKeepsStoredMediaTypeAndDownloadsWithSafeFilename() throws Exception {
        var docx = applications.docx("Đặng \"CV\".docx");
        jdbc.update("UPDATE application_cvs SET filename=?,content_type=?,content=?,size_bytes=?,sha256=? WHERE application_id=?",
                docx.filename(), docx.contentType(), docx.content(), docx.content().length, docx.sha256(), first.applicationId());
        HttpResponse<byte[]> response = cv(interview, panelToken);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(docx.content());
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertThat(response.headers().firstValue("Content-Disposition")).hasValueSatisfying(value ->
                assertThat(value).startsWith("attachment;").contains(".docx").doesNotContain("\r", "\n"));
    }

    @Test
    void missingReadOrEvaluationGrantsImmediatelyRemoveBothPreparationCapabilities() throws Exception {
        for (String permission : List.of("INTERVIEWS_READ_SCOPED", "EVALUATIONS_READ_SCOPED", "EVALUATIONS_WRITE_SCOPED")) {
            int removed = jdbc.update("DELETE FROM role_permissions WHERE role_code='INTERVIEWER' AND permission_code=?", permission);
            assertThat(removed).as(permission).isEqualTo(1);
            try {
                error(call("GET", CALENDAR, null, panelToken), 403, "FORBIDDEN");
                error(call("GET", cvPath(interview), null, panelToken), 403, "FORBIDDEN");
            } finally {
                jdbc.update("INSERT INTO role_permissions(role_code,permission_code) VALUES ('INTERVIEWER',?)", permission);
            }
        }
        assertThat(count("candidate_access_events")).isZero();
    }

    @Test
    void expiredRevokedAndAdministrativelyLockedSessionsCannotReadCalendarOrCv() throws Exception {
        for (String route : List.of(CALENDAR, cvPath(interview))) assertThat(call("GET", route, null, null).statusCode()).isEqualTo(401);
        jdbc.update("UPDATE auth_sessions SET revoked_at=? WHERE user_id=?", Timestamp.from(NOW), panel);
        for (String route : List.of(CALENDAR, cvPath(interview))) assertThat(call("GET", route, null, panelToken).statusCode()).isEqualTo(401);
        String fresh = token(panel);
        jdbc.update("UPDATE user_accounts SET admin_locked_at=?,admin_locked_by=?,admin_lock_reason='Testing' WHERE id=?", Timestamp.from(NOW), hr, panel);
        for (String route : List.of(CALENDAR, cvPath(interview))) assertThat(call("GET", route, null, fresh).statusCode()).isEqualTo(401);
        jdbc.update("UPDATE user_accounts SET admin_locked_at=NULL,admin_locked_by=NULL,admin_lock_reason=NULL WHERE id=?", panel);
        clock.set(NOW.plus(2, ChronoUnit.DAYS));
        for (String route : List.of(CALENDAR, cvPath(interview))) assertThat(call("GET", route, null, fresh).statusCode()).isEqualTo(401);
    }

    @Test
    void unknownInterviewAndMalformedIdentifiersDoNotCreateCandidateAudits() throws Exception {
        error(call("GET", cvPath(UUID.randomUUID()), null, panelToken), 404, "INTERVIEW_NOT_FOUND");
        error(call("GET", "/api/v1/interviews/not-a-uuid/cv", null, panelToken), 400, "VALIDATION_ERROR");
        assertThat(count("candidate_access_events")).isZero();
    }

    private void assertInactiveParentHidesPreparation(UUID future) throws Exception {
        assertThat(jdbc.queryForObject("SELECT active FROM interview_participants WHERE interview_id=? AND user_id=?",
                Boolean.class, future, panel)).isTrue();
        for (String query : List.of("", "?includePending=true", history())) {
            JsonNode result = calendar(query, panelToken);
            assertThat(result.path("totalElements").asLong()).isZero();
            assertThat(result.path("items").size()).isZero();
        }
        error(call("GET", cvPath(future), null, panelToken), 403, "FORBIDDEN");
        error(call("GET", cvPath(interview), null, panelToken), 403, "FORBIDDEN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_access_events WHERE actor_id=? AND application_id=? AND access_kind='CV' AND outcome='DENIED'",
                Long.class, panel, first.applicationId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_access_events WHERE actor_id=? AND outcome='GRANTED'",
                Long.class, panel)).isZero();
    }

    private UUID slot(UUID application, Instant startsAt, UUID invited) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(NOW);
        jdbc.update("""
                INSERT INTO interview_sessions(id,application_id,round_name,starts_at,ends_at,mode,room,normalized_room,
                    created_by,created_at,updated_at) VALUES (?,?,'Calendar round',?,?,'ONSITE','Calendar Room','calendar room',?,?,?)
                """, id, application, Timestamp.from(startsAt), Timestamp.from(startsAt.plusSeconds(3600)), hr, now, now);
        jdbc.update("INSERT INTO interview_participants(interview_id,user_id) VALUES (?,?)", id, invited);
        return id;
    }

    private JsonNode calendar(String query, String token) throws Exception { return ok(call("GET", CALENDAR + query, null, token)); }
    private static String cvPath(UUID id) { return "/api/v1/interviews/" + id + "/cv"; }
    private static String history() { return "?from=" + NOW.minus(1, ChronoUnit.DAYS) + "&to=" + NOW.plus(1, ChronoUnit.DAYS); }
    private static List<String> ids(JsonNode result) {
        var ids = new java.util.ArrayList<String>();
        for (JsonNode row : result.path("items")) ids.add(row.path("id").asText());
        return ids;
    }
    private static JsonNode item(JsonNode result, UUID id) {
        for (JsonNode row : result.path("items")) if (row.path("id").asText().equals(id.toString())) return row;
        throw new AssertionError("Missing appointment " + id);
    }
    private HttpResponse<byte[]> cv(UUID id, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + cvPath(id)))
                .header("Authorization", "Bearer " + token).GET().build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofByteArray());
    }
    private void error(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo(code);
    }
}
