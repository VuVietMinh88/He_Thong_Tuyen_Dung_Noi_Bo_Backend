package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.ttcs.recruitment.auth.DashboardChartsIntegrationTest.applications;
import static vn.ttcs.recruitment.auth.DashboardReportIntegrationTest.ids;
import static vn.ttcs.recruitment.auth.DashboardReportIntegrationTest.value;

/** Task 557 (story S8-05): the combined dashboard, its filters and the scope of every part. */
class DashboardScopeIntegrationTest extends ReportingTestSupport {
    static final String DASHBOARD = "/api/v1/reports/dashboard";
    static final String CHARTS = "/api/v1/reports/dashboard/charts";

    @Test
    void theDashboardCombinesCardsOverdueListAndChartsInOneResponse() throws Exception {
        JsonNode view = okJson(DASHBOARD, hrToken);
        JsonNode weekly = view.path("charts").path("weeklyApplications");
        assertThat(weekly.path("points")).hasSize(12);
        assertThat(weekly.path("points").path(11).path("applications").asLong())
                .isEqualTo(value(view, "newApplicationsThisWeek"));
        assertThat(view.path("charts").path("monthlyClosedRequisitions").path("points")).hasSize(12);
        assertThat(view.path("filters").path("departmentId").isNull()).isTrue();

        JsonNode ranged = okJson(DASHBOARD + "?from=2026-10-01&to=2026-10-15", hrToken).path("charts");
        assertThat(ranged.path("weeklyApplications").path("from").asText()).isEqualTo("2026-09-28");
        assertThat(ranged.path("monthlyClosedRequisitions").path("points")).hasSize(1);
        assertThat(okJson(CHARTS + "?from=2026-10-01&to=2026-10-15", hrToken).path("weeklyApplications"))
                .isEqualTo(ranged.path("weeklyApplications"));
    }

    @Test
    void aDepartmentFilterNarrowsEveryPartOfTheDashboard() throws Exception {
        requisitionState(requisition, "APPROVED", LocalDate.of(2026, 10, 1));
        requisitionState(otherRequisition, "APPROVED", LocalDate.of(2026, 10, 2));
        interview(first.applicationId(), NOW.plusSeconds(3600), "SCHEDULED");
        interview(other.applicationId(), NOW.plusSeconds(3600), "SCHEDULED");
        offer(application(requisition, "offer-a@example.test", NOW, "OFFER", null), "SENT", NOW.plusSeconds(86_400));
        offer(application(otherRequisition, "offer-b@example.test", NOW, "OFFER", null), "SENT", NOW.plusSeconds(86_400));
        UUID closed = newRequisition(otherDepartment, otherPosition, "FILLED", null, 1);
        workflowEvent(closed, "FILLED", "APPROVED", "FILLED", NOW.minusSeconds(3600));

        JsonNode all = okJson(DASHBOARD, hrToken);
        assertThat(value(all, "upcomingInterviews")).isEqualTo(2);
        assertThat(value(all, "pendingOffers")).isEqualTo(2);
        assertThat(lastMonthClosed(all)).isEqualTo(1);

        JsonNode filtered = okJson(DASHBOARD + "?departmentId=" + department, hrToken);
        assertThat(filtered.path("filters").path("departmentId").asText()).isEqualTo(department.toString());
        assertThat(value(filtered, "openRequisitions")).isEqualTo(1);
        assertThat(value(filtered, "newApplicationsThisWeek")).isEqualTo(2);
        assertThat(value(filtered, "upcomingInterviews")).isEqualTo(1);
        assertThat(value(filtered, "pendingOffers")).isEqualTo(1);
        assertThat(ids(filtered.path("overdueRequisitions"))).containsExactly(requisition.toString());
        assertThat(applications(filtered.path("charts").path("weeklyApplications")).getLast()).isEqualTo(2);
        assertThat(lastMonthClosed(filtered)).isZero();
        assertThat(lastMonthClosed(okJson(DASHBOARD + "?departmentId=" + otherDepartment, hrToken))).isEqualTo(1);
    }

    @Test
    void aPositionFilterSpansDepartmentsButStaysInsideTheScope() throws Exception {
        UUID samePosition = newRequisition(otherDepartment, position, "APPROVED", null, 1);
        application(samePosition, "same@example.test", NOW, "NEW", null);
        JsonNode hrView = okJson(DASHBOARD + "?positionId=" + position, hrToken);
        assertThat(value(hrView, "openRequisitions")).isEqualTo(2);
        assertThat(value(hrView, "newApplicationsThisWeek")).isEqualTo(2);
        JsonNode recruiterView = okJson(DASHBOARD + "?positionId=" + position, recruiterToken);
        assertThat(value(recruiterView, "openRequisitions")).isEqualTo(1);
        assertThat(value(recruiterView, "newApplicationsThisWeek")).isEqualTo(1);
    }

    @Test
    void filtersMustNameSomethingThatExistsAndIsInsideTheScope() throws Exception {
        status(get(DASHBOARD + "?departmentId=" + UUID.randomUUID(), hrToken), 404, "DEPARTMENT_NOT_FOUND");
        status(get(DASHBOARD + "?positionId=" + UUID.randomUUID(), hrToken), 404, "POSITION_NOT_FOUND");
        status(get(CHARTS + "?departmentId=" + UUID.randomUUID(), hrToken), 404, "DEPARTMENT_NOT_FOUND");
        status(get(DASHBOARD + "?departmentId=not-a-uuid", hrToken), 400, "VALIDATION_ERROR");
        // A permission refusal comes before the lookup.
        status(get(DASHBOARD + "?departmentId=" + UUID.randomUUID(), interviewerToken), 403, "FORBIDDEN");

        okJson(DASHBOARD + "?departmentId=" + department, recruiterToken);          // holds an assigned requisition there
        status(get(DASHBOARD + "?departmentId=" + otherDepartment, recruiterToken), 403, "FORBIDDEN");
        status(get(CHARTS + "?departmentId=" + otherDepartment, recruiterToken), 403, "FORBIDDEN");
        status(get(DASHBOARD + "?positionId=" + otherPosition, recruiterToken), 403, "FORBIDDEN");
        okJson(DASHBOARD + "?positionId=" + position, recruiterToken);

        UUID child = newDepartment("Phòng con", department, hr);                     // managed through the parent
        assertThat(value(okJson(DASHBOARD + "?departmentId=" + child, managerToken), "openRequisitions")).isZero();
        status(get(DASHBOARD + "?departmentId=" + otherDepartment, managerToken), 403, "FORBIDDEN");
        // HR may filter on any existing department, even an empty one.
        assertThat(value(okJson(DASHBOARD + "?departmentId=" + child, hrToken), "openRequisitions")).isZero();
    }

    @Test
    void aBroadReportGrantNeverWidensANarrowCandidateGrant() throws Exception {
        assertThat(value(okJson(DASHBOARD, hrToken), "openRequisitions")).isEqualTo(2);
        revoke("HR_MANAGER", "CANDIDATES_READ_ALL");
        grant("HR_MANAGER", "CANDIDATES_READ_SCOPED");
        JsonNode narrowed = okJson(DASHBOARD, hrToken);
        assertThat(narrowed.path("scope").asText()).isEqualTo("SCOPED");
        assertThat(value(narrowed, "openRequisitions")).isZero();
        assertThat(value(narrowed, "newApplicationsThisWeek")).isZero();
    }

    @Test
    void aRevokedGrantRefusesTheNextRequestWithTheSameToken() throws Exception {
        okJson(DASHBOARD, recruiterToken);
        revoke("RECRUITER", "REPORTS_READ_SCOPED");
        status(get(DASHBOARD, recruiterToken), 403, "FORBIDDEN");
        status(get(CHARTS, recruiterToken), 403, "FORBIDDEN");
        grant("RECRUITER", "REPORTS_READ_SCOPED");
        okJson(DASHBOARD, recruiterToken);
    }

    @Test
    void theDashboardDisclosesNoCandidatePersonalData() throws Exception {
        interview(first.applicationId(), NOW.plusSeconds(3600), "SCHEDULED");
        application(requisition, "private.person@example.test", Instant.parse("2026-10-14T01:00:00Z"), "NEW", null);
        String body = get(DASHBOARD, hrToken).body();
        assertThat(body).doesNotContain("lan@example.test", "private.person@example.test", "0912345678",
                ApplicationTestSupport.FULL_NAME, first.applicationId().toString());
    }

    private static long lastMonthClosed(JsonNode dashboard) {
        JsonNode points = dashboard.path("charts").path("monthlyClosedRequisitions").path("points");
        return points.path(points.size() - 1).path("closed").asLong();
    }
}
