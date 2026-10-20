package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Task 556 (story S8-05): weekly applications and monthly closed requisitions, zero-filled, inside the scope. */
class DashboardChartsIntegrationTest extends ReportingTestSupport {
    static final String CHARTS = "/api/v1/reports/dashboard/charts";

    @Test
    void defaultsAreTheLastTwelveWeeksAndTwelveMonthsWithEmptyBucketsAsZero() throws Exception {
        JsonNode view = okJson(CHARTS, hrToken);
        assertThat(view.path("scope").asText()).isEqualTo("ALL");
        JsonNode weekly = view.path("weeklyApplications");
        assertThat(weekly.path("points")).hasSize(12);
        assertThat(weekly.path("from").asText()).isEqualTo("2026-07-27");
        assertThat(weekly.path("to").asText()).isEqualTo("2026-10-18");
        JsonNode last = weekly.path("points").path(11);
        assertThat(last.path("weekStart").asText()).isEqualTo("2026-10-12");
        assertThat(last.path("weekEnd").asText()).isEqualTo("2026-10-18");
        assertThat(last.path("applications").asLong()).isEqualTo(2);
        assertThat(applications(weekly).subList(0, 11)).containsOnly(0L);

        JsonNode monthly = view.path("monthlyClosedRequisitions");
        assertThat(monthly.path("points")).hasSize(12);
        assertThat(monthly.path("from").asText()).isEqualTo("2025-11-01");
        assertThat(monthly.path("to").asText()).isEqualTo("2026-10-31");
        assertThat(monthly.path("points").path(0).path("month").asText()).isEqualTo("2025-11");
        monthly.path("points").forEach(point -> assertThat(point.path("closed").asLong()).isZero());
    }

    @Test
    void weeksAreWholeBusinessWeeksCountedInVietnamTime() throws Exception {
        received(first.applicationId(), Instant.parse("2026-08-30T17:00:00Z"));     // Monday 31 Aug 00:00
        received(other.applicationId(), Instant.parse("2026-08-30T16:59:59Z"));     // Sunday 30 Aug: before the range
        application(requisition, "a@example.test", Instant.parse("2026-09-06T16:59:59Z"), "NEW", null); // Sunday 6 Sep
        application(requisition, "b@example.test", Instant.parse("2026-09-06T17:00:00Z"), "NEW", null); // Monday 7 Sep
        application(otherRequisition, "c@example.test", Instant.parse("2026-10-14T05:00:00Z"), "REJECTED", null);

        JsonNode weekly = okJson(CHARTS + "?from=2026-09-02&to=2026-10-15", hrToken).path("weeklyApplications");
        assertThat(weekly.path("from").asText()).isEqualTo("2026-08-31");
        assertThat(weekly.path("to").asText()).isEqualTo("2026-10-18");
        assertThat(applications(weekly)).containsExactly(2L, 1L, 0L, 0L, 0L, 0L, 1L);

        JsonNode recruiterWeekly = okJson(CHARTS + "?from=2026-09-02&to=2026-10-15", recruiterToken)
                .path("weeklyApplications");
        assertThat(applications(recruiterWeekly)).containsExactly(2L, 1L, 0L, 0L, 0L, 0L, 0L);
    }

    @Test
    void closedRequisitionsAreCountedByTheMonthOfTheirFilledOrCancelledEvent() throws Exception {
        UUID septemberFill = newRequisition(department, position, "FILLED", null, 1);
        workflowEvent(septemberFill, "FILLED", "APPROVED", "FILLED", Instant.parse("2026-09-30T16:59:59Z"));
        UUID octoberCancel = newRequisition(department, position, "CANCELLED", null, 1);
        workflowEvent(octoberCancel, "CANCELLED", "PAUSED", "CANCELLED", Instant.parse("2026-09-30T17:00:00Z"));
        UUID octoberFill = newRequisition(otherDepartment, otherPosition, "FILLED", null, 1);
        workflowEvent(octoberFill, "FILLED", "APPROVED", "FILLED", Instant.parse("2026-10-14T03:00:00Z"));
        UUID resumed = newRequisition(department, position, "APPROVED", null, 1);    // a pause is not a closure
        workflowEvent(resumed, "PAUSED", "APPROVED", "PAUSED", Instant.parse("2026-10-02T03:00:00Z"));
        workflowEvent(resumed, "RESUMED", "PAUSED", "APPROVED", Instant.parse("2026-10-03T03:00:00Z"));

        JsonNode monthly = okJson(CHARTS + "?from=2026-08-15&to=2026-10-15", hrToken).path("monthlyClosedRequisitions");
        assertThat(monthly.path("from").asText()).isEqualTo("2026-08-01");
        assertThat(monthly.path("to").asText()).isEqualTo("2026-10-31");
        assertThat(monthly.path("points")).hasSize(3);
        JsonNode september = monthly.path("points").path(1);
        assertThat(september.path("month").asText()).isEqualTo("2026-09");
        assertThat(september.path("filled").asLong()).isEqualTo(1);
        assertThat(september.path("cancelled").asLong()).isZero();
        JsonNode october = monthly.path("points").path(2);
        assertThat(october.path("filled").asLong()).isEqualTo(1);
        assertThat(october.path("cancelled").asLong()).isEqualTo(1);
        assertThat(october.path("closed").asLong()).isEqualTo(2);

        // The manager of the first department sees its two closures, not the other department's.
        JsonNode managerMonthly = okJson(CHARTS + "?from=2026-08-15&to=2026-10-15", managerToken)
                .path("monthlyClosedRequisitions");
        assertThat(managerMonthly.path("points").path(1).path("closed").asLong()).isEqualTo(1);
        assertThat(managerMonthly.path("points").path(2).path("closed").asLong()).isEqualTo(1);
        assertThat(managerMonthly.path("points").path(2).path("filled").asLong()).isZero();
    }

    @Test
    void theRangeIsValidatedAndPermissionsComeFirst() throws Exception {
        status(get(CHARTS + "?from=2026-10-01", hrToken), 400, "VALIDATION_ERROR");
        status(get(CHARTS + "?from=2026-10-10&to=2026-10-01", hrToken), 400, "VALIDATION_ERROR");
        status(get(CHARTS + "?from=2025-10-01&to=2026-10-02", hrToken), 400, "VALIDATION_ERROR");
        status(get(CHARTS + "?from=2026-13-01&to=2026-10-02", hrToken), 400, "VALIDATION_ERROR");
        status(get(CHARTS + "?from=1999-12-31&to=2000-01-05", hrToken), 400, "VALIDATION_ERROR");
        assertThat(okJson(CHARTS + "?from=2025-10-02&to=2026-10-02", hrToken).path("monthlyClosedRequisitions")
                .path("points")).hasSize(13);
        assertThat(get(CHARTS, null).statusCode()).isEqualTo(401);
        status(get(CHARTS, interviewerToken), 403, "FORBIDDEN");
    }

    static List<Long> applications(JsonNode series) {
        List<Long> values = new ArrayList<>();
        series.path("points").forEach(point -> values.add(point.path("applications").asLong()));
        return values;
    }
}
