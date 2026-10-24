package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import vn.ttcs.recruitment.account.Role;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Task 563 (story S8-06): the department head's list of open requisitions with their progress counts. */
class RequisitionProgressListIntegrationTest extends ReportingTestSupport {
    static final String LIST = "/api/v1/reports/requisition-progress";

    @Test
    void aDepartmentHeadListsTheOpenRequisitionsOfItsDepartmentsMostUrgentFirst() throws Exception {
        requisitionState(requisition, "APPROVED", LocalDate.of(2026, 10, 30));
        UUID child = newDepartment("Phòng con", department, hr);
        UUID urgent = newRequisition(child, position, "PAUSED", LocalDate.of(2026, 10, 10), 2);
        UUID undated = newRequisition(department, position, "APPROVED", null, 1);
        newRequisition(department, position, "DRAFT", LocalDate.of(2026, 9, 1), 1);
        newRequisition(department, position, "FILLED", LocalDate.of(2026, 9, 1), 1);
        newRequisition(department, position, "CANCELLED", LocalDate.of(2026, 9, 1), 1);
        application(urgent, "u1@example.test", NOW, "INTERVIEW", null);
        application(urgent, "u2@example.test", NOW, "HIRED", null);
        application(urgent, "u3@example.test", NOW, "REJECTED", null);

        JsonNode page = okJson(LIST, managerToken);
        assertThat(page.path("scope").asText()).isEqualTo("SCOPED");
        assertThat(page.path("totalElements").asLong()).isEqualTo(3);
        assertThat(requisitionIds(page)).containsExactly(urgent.toString(), requisition.toString(), undated.toString());
        JsonNode top = page.path("items").path(0);
        assertThat(top.path("requisition").path("status").asText()).isEqualTo("PAUSED");
        assertThat(top.path("requisition").path("daysRemaining").asLong()).isEqualTo(-5);
        assertThat(top.path("requisition").path("hiredCount").asLong()).isEqualTo(1);
        assertThat(top.path("requisition").path("remainingHeadcount").asLong()).isEqualTo(1);
        assertThat(top.path("totalApplications").asLong()).isEqualTo(3);
        assertThat(top.path("activeApplications").asLong()).isEqualTo(1);
        assertThat(top.path("stageCounts").path(2).path("count").asLong()).isEqualTo(1);
        assertThat(page.path("items").path(1).path("requisition").path("daysRemaining").asLong()).isEqualTo(15);
        assertThat(page.path("items").path(2).path("requisition").path("daysRemaining").isNull()).isTrue();

        // The recruiter sees the one requisition it is assigned to; HR sees every open requisition.
        assertThat(requisitionIds(okJson(LIST, recruiterToken))).containsExactly(requisition.toString());
        assertThat(okJson(LIST, hrToken).path("totalElements").asLong()).isEqualTo(4);
        assertThat(okJson(LIST + "?departmentId=" + child, managerToken).path("totalElements").asLong()).isEqualTo(1);
        assertThat(okJson(LIST + "?positionId=" + otherPosition, hrToken).path("totalElements").asLong()).isEqualTo(1);

        JsonNode second = okJson(LIST + "?page=1&size=2", managerToken);
        assertThat(requisitionIds(second)).containsExactly(undated.toString());
        assertThat(second.path("totalPages").asInt()).isEqualTo(2);
    }

    @Test
    void countsFollowTheGatesOfTheDetailAndNameNoCandidate() throws Exception {
        UUID head = applications.account("Trưởng phòng", "head@example.test", Role.HIRING_MANAGER, Role.INTERVIEWER);
        UUID headDepartment = newDepartment("Phòng của trưởng phòng", null, head);
        UUID owned = newRequisition(headDepartment, position, "APPROVED", LocalDate.of(2026, 11, 1), 1);
        UUID ready = application(owned, "ready@example.test", NOW, "INTERVIEW", null);
        UUID ended = interview(ready, NOW.minus(Duration.ofDays(1)), "SCHEDULED");
        participant(ended, head, true);
        participant(ended, interviewer, true);
        evaluationForm(ended, ready, interviewer, "SUBMITTED");
        // The upcoming round belongs to another application: a scheduled later round would make the ended one no
        // longer the latest, and the application would not be ready for a decision.
        UUID second = application(owned, "second@example.test", NOW, "INTERVIEW", null);
        UUID upcoming = interview(second, NOW.plus(Duration.ofDays(2)), "SCHEDULED");
        participant(upcoming, head, true);

        JsonNode headPage = okJson(LIST, token(head));
        assertThat(headPage.path("lists").path("pendingEvaluations").path("available").asBoolean()).isTrue();
        JsonNode item = headPage.path("items").path(0);
        assertThat(item.path("requisition").path("requisitionId").asText()).isEqualTo(owned.toString());
        assertThat(item.path("upcomingInterviews").asLong()).isEqualTo(1);
        assertThat(item.path("pendingEvaluations").asLong()).isEqualTo(1);    // the head's own form of the ended round
        assertThat(item.path("awaitingDecision").asLong()).isEqualTo(1);      // a peer submitted the latest round

        // A plain HIRING_MANAGER writes no form: its pending count is withheld, not zero.
        JsonNode managerPage = okJson(LIST, managerToken);
        assertThat(managerPage.path("lists").path("pendingEvaluations").path("available").asBoolean()).isFalse();
        assertThat(managerPage.path("lists").path("pendingEvaluations").path("unavailableReason").asText())
                .isEqualTo("MISSING_EVALUATIONS_WRITE");
        assertThat(managerPage.path("items").path(0).path("pendingEvaluations").isNull()).isTrue();
        assertThat(managerPage.path("items").path(0).path("awaitingDecision").isNull()).isFalse();
        JsonNode recruiterPage = okJson(LIST, recruiterToken);
        assertThat(recruiterPage.path("lists").path("awaitingDecision").path("unavailableReason").asText())
                .isEqualTo("NOT_A_HIRING_DECIDER");
        assertThat(recruiterPage.path("items").path(0).path("awaitingDecision").isNull()).isTrue();

        long logged = jdbc.queryForObject("SELECT count(*) FROM candidate_access_events", Long.class);
        String body = get(LIST, hrToken).body();
        String readyName = jdbc.queryForObject("SELECT full_name FROM job_applications WHERE id = ?", String.class, ready);
        assertThat(body).doesNotContain("ready@example.test", readyName + "\"", "0912345678", ready.toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_access_events", Long.class)).isEqualTo(logged);
    }

    @Test
    void theListFollowsTheLiveScopeAndChecksItsFilters() throws Exception {
        assertThat(okJson(LIST, managerToken).path("totalElements").asLong()).isEqualTo(1);
        jdbc.update("UPDATE departments SET manager_user_id = ? WHERE id = ?", hr, department);
        assertThat(okJson(LIST, managerToken).path("totalElements").asLong()).isZero();
        assertThat(okJson(LIST, outsiderToken).path("totalElements").asLong()).isEqualTo(1);
        jdbc.update("DELETE FROM requisition_recruiters WHERE recruiter_id = ?", outsider);
        assertThat(okJson(LIST, outsiderToken).path("totalElements").asLong()).isZero();

        status(get(LIST + "?departmentId=" + UUID.randomUUID(), hrToken), 404, "DEPARTMENT_NOT_FOUND");
        status(get(LIST + "?departmentId=" + otherDepartment, recruiterToken), 403, "FORBIDDEN");
        status(get(LIST + "?size=51", hrToken), 400, "VALIDATION_ERROR");
        status(get(LIST, interviewerToken), 403, "FORBIDDEN");
        assertThat(get(LIST, null).statusCode()).isEqualTo(401);
    }

    static List<String> requisitionIds(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.path("items").forEach(item -> ids.add(item.path("requisition").path("requisitionId").asText()));
        return ids;
    }
}
