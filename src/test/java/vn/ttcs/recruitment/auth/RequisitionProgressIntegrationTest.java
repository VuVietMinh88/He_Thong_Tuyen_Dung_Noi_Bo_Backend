package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Task 562 (story S8-06): one requisition's progress, its lists, their gates and the disclosure log. */
class RequisitionProgressIntegrationTest extends ReportingTestSupport {

    String path(UUID requisitionId) {
        return "/api/v1/reports/requisitions/" + requisitionId + "/progress";
    }

    @Test
    void stageCountsCoverEveryStageOfTheRequisitionOnly() throws Exception {
        requisitionState(requisition, "APPROVED", LocalDate.of(2026, 10, 25));
        for (String stage : List.of("SCREENING", "INTERVIEW", "INTERVIEW", "OFFER", "HIRED", "ONBOARDED", "REJECTED",
                "WITHDRAWN")) {
            application(requisition, stage.toLowerCase() + UUID.randomUUID() + "@example.test", NOW, stage, null);
        }
        application(otherRequisition, "elsewhere@example.test", NOW, "INTERVIEW", null);

        JsonNode view = okJson(path(requisition), hrToken);
        assertThat(view.path("requisition").path("requisitionId").asText()).isEqualTo(requisition.toString());
        assertThat(view.path("requisition").path("neededBy").asText()).isEqualTo("2026-10-25");
        assertThat(view.path("requisition").path("daysRemaining").asLong()).isEqualTo(10);
        assertThat(view.path("requisition").path("headcount").asInt()).isEqualTo(1);
        assertThat(view.path("requisition").path("hiredCount").asLong()).isEqualTo(2);
        assertThat(view.path("requisition").path("remainingHeadcount").asLong()).isZero();
        List<String> stages = new ArrayList<>();
        List<Long> counts = new ArrayList<>();
        view.path("stageCounts").forEach(stage -> {
            stages.add(stage.path("stage").asText());
            counts.add(stage.path("count").asLong());
        });
        assertThat(stages).containsExactly("NEW", "SCREENING", "INTERVIEW", "OFFER", "HIRED", "ONBOARDED", "REJECTED",
                "WITHDRAWN");
        assertThat(counts).containsExactly(1L, 1L, 2L, 1L, 1L, 1L, 1L, 1L);
        assertThat(view.path("stageCounts").path(2).path("label").asText()).isEqualTo("Phỏng vấn");
        assertThat(view.path("totalApplications").asLong()).isEqualTo(9);
        assertThat(view.path("activeApplications").asLong()).isEqualTo(5);

        requisitionState(requisition, "APPROVED", LocalDate.of(2026, 10, 5));
        assertThat(okJson(path(requisition), hrToken).path("requisition").path("daysRemaining").asLong()).isEqualTo(-10);
        requisitionState(requisition, "APPROVED", null);
        assertThat(okJson(path(requisition), hrToken).path("requisition").path("daysRemaining").isNull()).isTrue();
    }

    @Test
    void upcomingInterviewsAreScheduledFutureSessionsOfOpenApplicationsSoonestFirst() throws Exception {
        UUID screening = application(requisition, "screen@example.test", NOW, "INTERVIEW", null);
        UUID later = interview(first.applicationId(), NOW.plus(Duration.ofDays(3)), "SCHEDULED");
        UUID soon = interview(screening, NOW.plusSeconds(7200), "SCHEDULED");
        participant(soon, manager, true);
        interview(screening, NOW.plusSeconds(3600), "CANCELLED");
        interview(screening, NOW.minusSeconds(7200), "SCHEDULED");
        UUID rejected = application(requisition, "gone@example.test", NOW, "REJECTED", null);
        interview(rejected, NOW.plusSeconds(3600), "SCHEDULED");
        interview(other.applicationId(), NOW.plusSeconds(3600), "SCHEDULED");

        JsonNode upcoming = okJson(path(requisition), managerToken).path("upcomingInterviews");
        assertThat(upcoming.path("available").asBoolean()).isTrue();
        assertThat(upcoming.path("total").asLong()).isEqualTo(2);
        assertThat(upcoming.path("limit").asInt()).isEqualTo(20);
        assertThat(field(upcoming, "interviewId")).containsExactly(soon.toString(), later.toString());
        JsonNode first = upcoming.path("items").path(0);
        assertThat(first.path("viewerOnPanel").asBoolean()).isTrue();
        assertThat(first.path("candidateName").asText()).startsWith("Ứng viên");
        assertThat(first.path("roundName").asText()).isEqualTo("Vòng kỹ thuật");
        assertThat(first.path("mode").asText()).isEqualTo("ONSITE");
        assertThat(upcoming.path("items").path(1).path("viewerOnPanel").asBoolean()).isFalse();

        revoke("HIRING_MANAGER", "INTERVIEWS_READ_SCOPED");
        JsonNode hidden = okJson(path(requisition), managerToken).path("upcomingInterviews");
        assertThat(hidden.path("available").asBoolean()).isFalse();
        assertThat(hidden.path("unavailableReason").asText()).isEqualTo("MISSING_INTERVIEWS_READ");
        assertThat(hidden.path("items")).isEmpty();
    }

    @Test
    void pendingEvaluationsAreTheViewersOwnFormsOfEndedInterviews() throws Exception {
        UUID notStarted = interview(first.applicationId(), NOW.minus(Duration.ofDays(2)), "SCHEDULED");
        participant(notStarted, hr, true);
        UUID draft = interview(first.applicationId(), NOW.minus(Duration.ofDays(1)), "SCHEDULED");
        participant(draft, hr, true);
        evaluationForm(draft, first.applicationId(), hr, "DRAFT");
        UUID submitted = interview(first.applicationId(), NOW.minus(Duration.ofHours(30)), "SCHEDULED");
        participant(submitted, hr, true);
        evaluationForm(submitted, first.applicationId(), hr, "SUBMITTED");
        UUID removed = interview(first.applicationId(), NOW.minus(Duration.ofHours(20)), "SCHEDULED");
        participant(removed, hr, false);
        UUID cancelled = interview(first.applicationId(), NOW.minus(Duration.ofHours(10)), "CANCELLED");
        participant(cancelled, hr, true);
        UUID running = interview(first.applicationId(), NOW.minusSeconds(1800), "SCHEDULED");   // ends in 30 minutes
        participant(running, hr, true);
        UUID peersOnly = interview(first.applicationId(), NOW.minus(Duration.ofHours(5)), "SCHEDULED");
        participant(peersOnly, manager, true);
        UUID elsewhere = interview(other.applicationId(), NOW.minus(Duration.ofDays(1)), "SCHEDULED");
        participant(elsewhere, hr, true);

        JsonNode pending = okJson(path(requisition), hrToken).path("pendingEvaluations");
        assertThat(pending.path("available").asBoolean()).isTrue();
        assertThat(pending.path("total").asLong()).isEqualTo(2);
        assertThat(field(pending, "interviewId")).containsExactly(notStarted.toString(), draft.toString());
        assertThat(field(pending, "formStatus")).containsExactly("NOT_STARTED", "DRAFT");

        // HIRING_MANAGER holds no EVALUATIONS write grant: it never has a form of its own to fill.
        JsonNode managerPending = okJson(path(requisition), managerToken).path("pendingEvaluations");
        assertThat(managerPending.path("available").asBoolean()).isFalse();
        assertThat(managerPending.path("unavailableReason").asText()).isEqualTo("MISSING_EVALUATIONS_WRITE");
    }

    @Test
    void applicationsAwaitingADecisionNeedASubmittedFormOnTheirLatestInterview() throws Exception {
        UUID ready = application(requisition, "ready@example.test", NOW, "INTERVIEW", null);
        UUID readyInterview = interview(ready, NOW.minus(Duration.ofDays(1)), "SCHEDULED");
        participant(readyInterview, interviewer, true);
        evaluationForm(readyInterview, ready, interviewer, "SUBMITTED");

        UUID stale = application(requisition, "stale@example.test", NOW, "INTERVIEW", null);
        UUID earlier = interview(stale, NOW.minus(Duration.ofDays(3)), "SCHEDULED");
        participant(earlier, interviewer, true);
        evaluationForm(earlier, stale, interviewer, "SUBMITTED");
        UUID newer = interview(stale, NOW.minus(Duration.ofDays(1)), "SCHEDULED");   // the latest: no submitted form yet
        participant(newer, interviewer, true);

        UUID removedPanel = application(requisition, "removed@example.test", NOW, "INTERVIEW", null);
        UUID removedInterview = interview(removedPanel, NOW.minus(Duration.ofDays(1)), "SCHEDULED");
        participant(removedInterview, interviewer, false);
        evaluationForm(removedInterview, removedPanel, interviewer, "SUBMITTED");

        UUID offered = application(requisition, "offered@example.test", NOW, "OFFER", null);
        UUID offeredInterview = interview(offered, NOW.minus(Duration.ofDays(1)), "SCHEDULED");
        participant(offeredInterview, interviewer, true);
        evaluationForm(offeredInterview, offered, interviewer, "SUBMITTED");

        JsonNode awaiting = okJson(path(requisition), managerToken).path("awaitingDecision");
        assertThat(awaiting.path("available").asBoolean()).isTrue();
        assertThat(awaiting.path("total").asLong()).isEqualTo(1);
        assertThat(field(awaiting, "applicationId")).containsExactly(ready.toString());
        assertThat(awaiting.path("items").path(0).path("interviewId").asText()).isEqualTo(readyInterview.toString());
        assertThat(awaiting.path("items").path(0).path("submittedEvaluations").asLong()).isEqualTo(1);
        assertThat(field(okJson(path(requisition), hrToken).path("awaitingDecision"), "applicationId"))
                .containsExactly(ready.toString());

        JsonNode recruiterView = okJson(path(requisition), recruiterToken).path("awaitingDecision");
        assertThat(recruiterView.path("available").asBoolean()).isFalse();
        assertThat(recruiterView.path("unavailableReason").asText()).isEqualTo("NOT_A_HIRING_DECIDER");

        // A paused requisition takes no decision.
        requisitionState(requisition, "PAUSED", null);
        assertThat(okJson(path(requisition), managerToken).path("awaitingDecision").path("total").asLong()).isZero();
    }

    @Test
    void theRequisitionMustExistAndBeInsideTheScopeAndDisclosuresAreLogged() throws Exception {
        status(get(path(UUID.randomUUID()), hrToken), 404, "REQUISITION_NOT_FOUND");
        status(get(path(requisition), outsiderToken), 403, "FORBIDDEN");
        status(get(path(requisition), approverToken), 403, "FORBIDDEN");
        status(get(path(UUID.randomUUID()), interviewerToken), 403, "FORBIDDEN");
        status(get(path("not-a-uuid"), hrToken), 400, "VALIDATION_ERROR");
        assertThat(get(path(requisition), null).statusCode()).isEqualTo(401);

        UUID soon = interview(first.applicationId(), NOW.plusSeconds(3600), "SCHEDULED");
        participant(soon, manager, true);
        long before = jdbc.queryForObject("SELECT count(*) FROM candidate_access_events WHERE actor_id = ?", Long.class, manager);
        String body = get(path(requisition), managerToken).body();
        assertThat(body).doesNotContain("lan@example.test", "0912345678");
        var logged = jdbc.queryForList("""
                SELECT application_id, access_kind, access_scope, outcome FROM candidate_access_events
                WHERE actor_id = ? ORDER BY occurred_at""", manager);
        assertThat(logged).hasSize((int) before + 1);
        assertThat(logged.getLast()).containsEntry("application_id", first.applicationId())
                .containsEntry("access_kind", "PROFILE").containsEntry("access_scope", "SCOPED")
                .containsEntry("outcome", "GRANTED");

        // The manager of a department loses the requisition as soon as the department changes hands.
        jdbc.update("UPDATE departments SET manager_user_id = ? WHERE id = ?", hr, department);
        status(get(path(requisition), managerToken), 403, "FORBIDDEN");
    }

    String path(String id) {
        return "/api/v1/reports/requisitions/" + id + "/progress";
    }

    static List<String> field(JsonNode list, String name) {
        List<String> values = new ArrayList<>();
        list.path("items").forEach(item -> values.add(item.path(name).asText()));
        return values;
    }
}
