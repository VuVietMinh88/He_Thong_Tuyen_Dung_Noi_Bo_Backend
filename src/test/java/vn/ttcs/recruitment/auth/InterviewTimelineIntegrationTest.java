package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.JsonNode;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static vn.ttcs.recruitment.auth.ApplicationTestSupport.NOW;

/** Real HTTP and migrated PostgreSQL checks for interview metadata in the exact application's timeline. */
class InterviewTimelineIntegrationTest extends ScreeningTestSupport {
    private static final String PRIVATE_LINK = "https://meet.example.test/private-interview-link";
    private static final String PRIVATE_REASON = "PRIVATE_INTERVIEW_CHANGE_REASON";
    private static final String PRIVATE_ROOM = "PRIVATE_INTERVIEW_ROOM";
    private static final String PRIVATE_COMMENT = "CONFIDENTIAL_EVALUATION_COMMENT";
    private static final String PRIVATE_FRAMEWORK = "CONFIDENTIAL_FRAMEWORK_NAME";
    private static final String PRIVATE_REOPEN_REASON = "CONFIDENTIAL_REOPEN_REASON";
    private static final String SUBMISSION_SNAPSHOT = """
            {"conclusion":"HIRE","totalScore":4,"items":[{"score":4,
            "comment":"CONFIDENTIAL_EVALUATION_COMMENT","name":"CONFIDENTIAL_CRITERION"}]}
            """;

    @Test
    void interviewHistoryKeepsRecordedRoundAndTimesWhenTheCurrentScheduleChanges() throws Exception {
        UUID interview = interview(first.applicationId());
        UUID scheduled = interviewEvent(interview, first.applicationId(), "INTERVIEW_SCHEDULED", recruiter,
                "Technical round", 1, 3600);
        UUID rescheduled = interviewEvent(interview, first.applicationId(), "INTERVIEW_RESCHEDULED", hr,
                "Technical follow-up", 2, 7200);
        UUID cancelled = interviewEvent(interview, first.applicationId(), "INTERVIEW_CANCELLED", hr,
                "Technical follow-up", 3, 7200);
        // Mutable appointment data must not rewrite earlier event snapshots or expose location/private explanations.
        jdbc.update("UPDATE interview_sessions SET round_name=?,starts_at=?,ends_at=?,mode='ONSITE',"
                        + "room=?,normalized_room=?,meeting_url=NULL,status='CANCELLED' WHERE id=?",
                "Current name", at(10800), at(12600), PRIVATE_ROOM, PRIVATE_ROOM.toLowerCase(), interview);
        jdbc.update("UPDATE user_accounts SET full_name=? WHERE id=?", "Recruiter current name", recruiter);

        JsonNode page = timeline(first.applicationId());
        assertThat(ids(page)).containsExactly("interview:" + cancelled, "interview:" + rescheduled,
                "interview:" + scheduled, "submission:" + first.applicationId());
        assertInterview(page.path("items").get(0), interview, "INTERVIEW_CANCELLED", "Technical follow-up", 3, 7200, hr);
        assertInterview(page.path("items").get(1), interview, "INTERVIEW_RESCHEDULED", "Technical follow-up", 2, 7200, hr);
        JsonNode created = page.path("items").get(2);
        assertInterview(created, interview, "INTERVIEW_SCHEDULED", "Technical round", 1, 3600, recruiter);
        assertThat(created.path("actor").path("displayName").asText()).isEqualTo("Recruiter current name");
        assertThat(page.toString()).doesNotContain(PRIVATE_LINK, PRIVATE_ROOM, PRIVATE_REASON, "Current name",
                "meetingUrl", "room", "reason", "@example.test");
    }

    @Test
    void interviewAndEvaluationEventsStayInTheExactApplicationEvenForTheSameCandidate() throws Exception {
        UUID ownInterview = interview(first.applicationId());
        UUID otherInterview = interview(other.applicationId());
        UUID ownEvent = interviewEvent(ownInterview, first.applicationId(), "INTERVIEW_SCHEDULED", recruiter,
                "Own round", 1, 3600);
        UUID otherEvent = interviewEvent(otherInterview, other.applicationId(), "INTERVIEW_RESCHEDULED", outsider,
                "Other application private round", 2, 7200);
        UUID ownForm = form(ownInterview, first.applicationId());
        UUID otherForm = form(otherInterview, other.applicationId());
        UUID ownEvaluation = evaluationEvent(ownInterview, first.applicationId(), ownForm, "EVALUATION_SUBMITTED", manager, 3);
        UUID otherEvaluation = evaluationEvent(otherInterview, other.applicationId(), otherForm,
                "EVALUATION_REOPENED", hr, 4);
        assertThat(first.candidateId()).isEqualTo(other.candidateId());

        JsonNode own = timeline(first.applicationId());
        assertThat(ids(own)).containsExactly("evaluation:" + ownEvaluation, "interview:" + ownEvent,
                "submission:" + first.applicationId());
        assertThat(own.toString()).doesNotContain(otherInterview.toString(), otherEvent.toString(),
                other.applicationId().toString(), otherForm.toString(), otherEvaluation.toString(),
                "Other application private round");
        assertThat(call("GET", pathFor(other.applicationId()), null, recruiterToken).statusCode()).isEqualTo(403);
        JsonNode otherPage = ok(call("GET", pathFor(other.applicationId()), null, hrToken));
        assertThat(ids(otherPage)).containsExactly("evaluation:" + otherEvaluation, "interview:" + otherEvent,
                "submission:" + other.applicationId());
        assertThat(otherPage.toString()).doesNotContain(ownInterview.toString(), ownEvent.toString(), ownForm.toString(),
                ownEvaluation.toString(), "Own round");
    }

    @Test
    void enteringInterviewStageOrHavingAnAppointmentAndDraftDoesNotFabricateHistory() throws Exception {
        jdbc.update("UPDATE job_applications SET stage='INTERVIEW' WHERE id=?", first.applicationId());
        UUID interview = interview(first.applicationId());
        UUID draft = form(interview, first.applicationId());
        jdbc.update("UPDATE evaluation_form_items SET score=5,comment=? WHERE form_id=?", "Updated draft", draft);
        JsonNode page = timeline(first.applicationId());
        assertThat(ids(page)).containsExactly("submission:" + first.applicationId());
        assertThat(page.path("totalElements").asInt()).isOne();
    }

    @Test
    void mixedSourcesHaveStableTiedOrderingAndPaginationIncludesNewEventsInTheTotal() throws Exception {
        UUID interview = interview(first.applicationId());
        UUID scheduled = interviewEvent(interview, first.applicationId(), "INTERVIEW_SCHEDULED", recruiter,
                "First round", 10, 3600);
        UUID rescheduled = interviewEvent(interview, first.applicationId(), "INTERVIEW_RESCHEDULED", hr,
                "Next round", 10, 7200);
        UUID form = form(interview, first.applicationId());
        UUID submitted = evaluationEvent(interview, first.applicationId(), form, "EVALUATION_SUBMITTED", manager, 10);
        UUID reopened = evaluationEvent(interview, first.applicationId(), form, "EVALUATION_REOPENED", hr, 10);
        UUID note = UUID.randomUUID();
        jdbc.update("INSERT INTO application_screening_notes(id,application_id,content,author_id,created_at)"
                + " VALUES (?,?,?,?,?)", note, first.applicationId(), "Internal screening note", hr, at(10));
        List<String> ties = new ArrayList<>(List.of("interview:" + scheduled, "interview:" + rescheduled,
                "evaluation:" + submitted, "evaluation:" + reopened, "note:" + note));
        ties.sort(Comparator.naturalOrder());
        List<String> expected = new ArrayList<>(ties);
        expected.add("submission:" + first.applicationId());

        assertThat(ids(timeline(first.applicationId()))).containsExactlyElementsOf(expected);
        List<String> paged = new ArrayList<>();
        for (int index = 0; index < expected.size(); index++) {
            JsonNode slice = ok(call("GET", pathFor(first.applicationId()) + "?page=" + index + "&size=1", null,
                    recruiterToken));
            assertThat(slice.path("totalElements").asInt()).isEqualTo(expected.size());
            assertThat(slice.path("items").size()).isOne();
            paged.addAll(ids(slice));
        }
        assertThat(paged).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
        JsonNode pastEnd = ok(call("GET", pathFor(first.applicationId()) + "?page=" + expected.size() + "&size=1",
                null, recruiterToken));
        assertThat(pastEnd.path("items").size()).isZero();
        assertThat(pastEnd.path("totalElements").asInt()).isEqualTo(expected.size());
    }

    @Test
    void submittedAndReopenedEventsKeepMetadataWithoutRevealingSnapshotOrCurrentDraft() throws Exception {
        UUID interview = interview(first.applicationId());
        UUID form = form(interview, first.applicationId());
        jdbc.update("UPDATE evaluation_forms SET status='SUBMITTED',conclusion='HIRE',total_score=4,"
                + "first_submitted_at=?,submitted_at=? WHERE id=?", at(1), at(1), form);
        UUID submitted = evaluationEvent(interview, first.applicationId(), form, "EVALUATION_SUBMITTED", manager, 1);
        UUID reopened = evaluationEvent(interview, first.applicationId(), form, "EVALUATION_REOPENED", recruiter, 2);
        jdbc.update("UPDATE evaluation_forms SET status='DRAFT',submitted_at=NULL,conclusion='NO_HIRE',total_score=1"
                + " WHERE id=?", form);
        jdbc.update("UPDATE evaluation_form_items SET score=1,comment=? WHERE form_id=?", "NEW_PRIVATE_DRAFT", form);

        JsonNode page = timeline(first.applicationId());
        assertThat(ids(page)).containsExactly("evaluation:" + reopened, "evaluation:" + submitted,
                "submission:" + first.applicationId());
        assertEvaluation(page.path("items").get(0), interview, form, "EVALUATION_REOPENED", recruiter, 2);
        assertEvaluation(page.path("items").get(1), interview, form, "EVALUATION_SUBMITTED", manager, 1);
        assertThat(page.toString()).doesNotContain(PRIVATE_COMMENT, PRIVATE_FRAMEWORK, PRIVATE_REOPEN_REASON,
                "NEW_PRIVATE_DRAFT", "CONFIDENTIAL_CRITERION", "DRAFT", "HIRE", "NO_HIRE", "comment", "score",
                "totalScore", "conclusion", "submissionSnapshot", "framework", "reason", "@example.test");
        // A real confidential, immutable payload exists; filtering must not depend on it being absent or the form
        // remaining submitted. Later draft edits must never overwrite the previous submitted content.
        String stored = jdbc.queryForObject("SELECT submission_snapshot::text FROM evaluation_events WHERE id=?",
                String.class, submitted);
        assertThat(stored).contains(PRIVATE_COMMENT, "HIRE", "CONFIDENTIAL_CRITERION").doesNotContain("NEW_PRIVATE_DRAFT");
        assertThatThrownBy(() -> jdbc.update("UPDATE evaluation_events SET submission_snapshot='{}'::jsonb WHERE id=?",
                submitted)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(ids(timeline(first.applicationId()))).containsExactlyElementsOf(ids(page));
    }

    @Test
    void publicTrackingNeverGainsInterviewOrEvaluationMetadata() throws Exception {
        UUID interview = interview(first.applicationId());
        UUID scheduled = interviewEvent(interview, first.applicationId(), "INTERVIEW_SCHEDULED", recruiter,
                "PRIVATE_ROUND_FOR_TRACKING", 1, 3600);
        UUID form = form(interview, first.applicationId());
        UUID submitted = evaluationEvent(interview, first.applicationId(), form, "EVALUATION_SUBMITTED", manager, 2);

        var response = applications.lookup(first.trackingCode(), "lan@example.test");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode body = json.readTree(response.body());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("postingTitle", "receivedAt", "status", "statusName", "actions");
        assertThat(response.body()).doesNotContain(interview.toString(), scheduled.toString(), form.toString(),
                submitted.toString(), "PRIVATE_ROUND_FOR_TRACKING", PRIVATE_LINK, PRIVATE_COMMENT,
                "INTERVIEW_SCHEDULED", "EVALUATION_SUBMITTED", "timeline", "actor");
    }

    private UUID interview(UUID application) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO interview_sessions(id,application_id,round_name,starts_at,ends_at,mode,meeting_url,"
                        + "created_by,created_at,updated_at) VALUES (?,?,?,?,?,'ONLINE',?,?,?,?)",
                id, application, "Technical round", at(3600), at(5400), PRIVATE_LINK, hr, at(0), at(0));
        jdbc.update("INSERT INTO interview_participants(interview_id,user_id) VALUES (?,?)", id, manager);
        return id;
    }

    private UUID interviewEvent(UUID interview, UUID application, String type, UUID actor, String round,
                                int occurredOffset, int startsOffset) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO interview_events(id,interview_id,application_id,event_type,actor_id,occurred_at,"
                        + "starts_at,ends_at,round_name,reason) VALUES (?,?,?,?,?,?,?,?,?,?)",
                id, interview, application, type, actor, at(occurredOffset), at(startsOffset), at(startsOffset + 1800),
                round, PRIVATE_REASON);
        return id;
    }

    private UUID form(UUID interview, UUID application) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO evaluation_forms(id,interview_id,application_id,evaluator_id,framework_id,"
                        + "framework_code,framework_name,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?)",
                id, interview, application, manager, UUID.randomUUID(), "PRIVATE_FRAMEWORK", PRIVATE_FRAMEWORK, at(0), at(0));
        jdbc.update("INSERT INTO evaluation_form_items(form_id,criterion_id,name,description,weight,sort_order,score,comment)"
                        + " VALUES (?,?,?,?,100,1,4,?)", id, UUID.randomUUID(), "CONFIDENTIAL_CRITERION",
                "Confidential evaluation criterion description", PRIVATE_COMMENT);
        return id;
    }

    private UUID evaluationEvent(UUID interview, UUID application, UUID form, String type, UUID actor, int offset) {
        UUID id = UUID.randomUUID();
        boolean submitted = "EVALUATION_SUBMITTED".equals(type);
        jdbc.update("INSERT INTO evaluation_events(id,interview_id,application_id,form_id,event_type,actor_id,"
                        + "occurred_at,reason,submission_snapshot) VALUES (?,?,?,?,?,?,?,?,?::jsonb)",
                id, interview, application, form, type, actor, at(offset), submitted ? null : PRIVATE_REOPEN_REASON,
                submitted ? SUBMISSION_SNAPSHOT : null);
        return id;
    }

    private JsonNode timeline(UUID application) throws Exception {
        return ok(call("GET", pathFor(application), null, recruiterToken));
    }

    private static String pathFor(UUID application) { return "/api/v1/applications/" + application + "/timeline"; }

    private static List<String> ids(JsonNode page) {
        List<String> result = new ArrayList<>();
        page.path("items").forEach(item -> result.add(item.path("id").asText()));
        return result;
    }

    private static Timestamp at(int seconds) { return Timestamp.from(NOW.plusSeconds(seconds)); }

    private static void assertEvaluation(JsonNode event, UUID interview, UUID form, String type, UUID actor, int offset) {
        assertThat(event.path("type").asText()).isEqualTo(type);
        assertThat(Instant.parse(event.path("occurredAt").asText())).isEqualTo(NOW.plusSeconds(offset));
        assertThat(event.path("actor").path("type").asText()).isEqualTo("STAFF");
        assertThat(event.path("actor").path("id").asText()).isEqualTo(actor.toString());
        JsonNode details = event.path("details");
        assertThat(details.propertyNames()).containsExactlyInAnyOrder("interviewId", "formId");
        assertThat(details.path("interviewId").asText()).isEqualTo(interview.toString());
        assertThat(details.path("formId").asText()).isEqualTo(form.toString());
    }

    private static void assertInterview(JsonNode event, UUID interview, String type, String round, int eventOffset,
                                        int startsOffset, UUID actor) {
        assertThat(event.path("type").asText()).isEqualTo(type);
        assertThat(Instant.parse(event.path("occurredAt").asText())).isEqualTo(NOW.plusSeconds(eventOffset));
        assertThat(event.path("actor").path("type").asText()).isEqualTo("STAFF");
        assertThat(event.path("actor").path("id").asText()).isEqualTo(actor.toString());
        JsonNode details = event.path("details");
        assertThat(details.propertyNames()).containsExactlyInAnyOrder("interviewId", "roundName", "startsAt", "endsAt");
        assertThat(details.path("interviewId").asText()).isEqualTo(interview.toString());
        assertThat(details.path("roundName").asText()).isEqualTo(round);
        assertThat(Instant.parse(details.path("startsAt").asText())).isEqualTo(NOW.plusSeconds(startsOffset));
        assertThat(Instant.parse(details.path("endsAt").asText())).isEqualTo(NOW.plusSeconds(startsOffset + 1800));
    }
}
