package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Task 555 (story S8-05): the dashboard's cards and overdue requisitions, through HTTP on a temporary database. */
class DashboardReportIntegrationTest extends ReportingTestSupport {
    static final String DASHBOARD = "/api/v1/reports/dashboard";

    @Test
    void cardsCountOnlyTheRequisitionsAndApplicationsInTheViewersScope() throws Exception {
        JsonNode hrView = okJson(DASHBOARD, hrToken);
        assertThat(hrView.path("scope").asText()).isEqualTo("ALL");
        assertThat(hrView.path("businessDate").asText()).isEqualTo("2026-10-15");
        assertThat(hrView.path("businessZone").asText()).isEqualTo("Asia/Ho_Chi_Minh");
        assertThat(value(hrView, "openRequisitions")).isEqualTo(2);
        assertThat(value(hrView, "pausedRequisitions")).isZero();
        assertThat(value(hrView, "newApplicationsThisWeek")).isEqualTo(2);
        assertThat(hrView.path("currentWeek").path("from").asText()).isEqualTo("2026-10-12");
        assertThat(hrView.path("currentWeek").path("to").asText()).isEqualTo("2026-10-18");
        assertThat(hrView.path("interviewHorizonDays").asInt()).isEqualTo(7);

        JsonNode recruiterView = okJson(DASHBOARD, recruiterToken);
        assertThat(recruiterView.path("scope").asText()).isEqualTo("SCOPED");
        assertThat(value(recruiterView, "openRequisitions")).isEqualTo(1);
        assertThat(value(recruiterView, "newApplicationsThisWeek")).isEqualTo(1);
        assertThat(value(okJson(DASHBOARD, outsiderToken), "openRequisitions")).isEqualTo(1);

        // The manager of a department also sees the departments below it, whoever manages them.
        UUID child = newDepartment("Phòng con", department, hr);
        UUID childRequisition = newRequisition(child, position, "PAUSED", null, 1);
        application(childRequisition, "child@example.test", NOW, "NEW", null);
        JsonNode managerView = okJson(DASHBOARD, managerToken);
        assertThat(value(managerView, "openRequisitions")).isEqualTo(1);
        assertThat(value(managerView, "pausedRequisitions")).isEqualTo(1);
        assertThat(value(managerView, "newApplicationsThisWeek")).isEqualTo(2);
        // APPROVER holds REPORTS_READ_ALL but scoped requisition and candidate reads: nothing assigned, nothing managed.
        JsonNode approverView = okJson(DASHBOARD, approverToken);
        assertThat(approverView.path("scope").asText()).isEqualTo("SCOPED");
        assertThat(value(approverView, "openRequisitions")).isZero();
        assertThat(value(approverView, "newApplicationsThisWeek")).isZero();
    }

    @Test
    void scopeFollowsTheCurrentAssignmentAndDepartmentManager() throws Exception {
        assertThat(value(okJson(DASHBOARD, recruiterToken), "openRequisitions")).isEqualTo(1);
        jdbc.update("DELETE FROM requisition_recruiters WHERE requisition_id = ? AND recruiter_id = ?", requisition, recruiter);
        assertThat(value(okJson(DASHBOARD, recruiterToken), "openRequisitions")).isZero();
        assertThat(value(okJson(DASHBOARD, recruiterToken), "newApplicationsThisWeek")).isZero();

        assertThat(value(okJson(DASHBOARD, managerToken), "openRequisitions")).isEqualTo(1);
        jdbc.update("UPDATE departments SET manager_user_id = ? WHERE id = ?", hr, department);
        assertThat(value(okJson(DASHBOARD, managerToken), "openRequisitions")).isZero();
    }

    @Test
    void theWeekCardUsesTheBusinessWeekFromMondayMidnightInVietnam() throws Exception {
        received(first.applicationId(), Instant.parse("2026-10-11T16:59:59Z"));   // Sunday 23:59:59, last week
        received(other.applicationId(), Instant.parse("2026-10-11T17:00:00Z"));   // Monday 00:00
        application(requisition, "sunday@example.test", Instant.parse("2026-10-18T16:59:59Z"), "NEW", null);
        application(requisition, "next@example.test", Instant.parse("2026-10-18T17:00:00Z"), "NEW", null);
        assertThat(value(okJson(DASHBOARD, hrToken), "newApplicationsThisWeek")).isEqualTo(2);

        // 18:00 UTC on Sunday is already Monday 01:00 in Vietnam: the new week has begun there.
        clock.set(Instant.parse("2026-10-18T18:00:00Z"));
        JsonNode monday = okJson(DASHBOARD, token(hr));
        assertThat(monday.path("businessDate").asText()).isEqualTo("2026-10-19");
        assertThat(monday.path("currentWeek").path("from").asText()).isEqualTo("2026-10-19");
        assertThat(value(monday, "newApplicationsThisWeek")).isEqualTo(1);
    }

    @Test
    void upcomingInterviewsAreScheduledSessionsOfTheNextSevenDaysForOpenApplications() throws Exception {
        interview(first.applicationId(), NOW.plusSeconds(3600), "SCHEDULED");
        interview(first.applicationId(), NOW.plus(Duration.ofDays(7)).minusSeconds(1), "SCHEDULED");
        interview(first.applicationId(), NOW.plus(Duration.ofDays(7)), "SCHEDULED");      // the horizon is exclusive
        interview(first.applicationId(), NOW.minusSeconds(60), "SCHEDULED");              // already started
        interview(first.applicationId(), NOW.plusSeconds(7200), "CANCELLED");
        UUID rejected = application(requisition, "rejected@example.test", NOW, "REJECTED", null);
        interview(rejected, NOW.plusSeconds(3600), "SCHEDULED");                           // no longer in the pipeline
        interview(other.applicationId(), NOW.plusSeconds(3600), "SCHEDULED");
        assertThat(value(okJson(DASHBOARD, hrToken), "upcomingInterviews")).isEqualTo(3);
        assertThat(value(okJson(DASHBOARD, recruiterToken), "upcomingInterviews")).isEqualTo(2);
        assertThat(value(okJson(DASHBOARD, managerToken), "upcomingInterviews")).isEqualTo(2);

        JsonNode approverCard = okJson(DASHBOARD, approverToken).path("cards").path("upcomingInterviews");
        assertThat(approverCard.path("available").asBoolean()).isFalse();
        assertThat(approverCard.path("value").isNull()).isTrue();
        assertThat(approverCard.path("unavailableReason").asText()).isEqualTo("MISSING_INTERVIEWS_READ");
    }

    @Test
    void pendingOffersAreSentOffersWhoseDeadlineHasNotPassed() throws Exception {
        offer(application(requisition, "o1@example.test", NOW, "OFFER", null), "SENT", NOW.plusSeconds(86_400));
        offer(application(requisition, "o2@example.test", NOW, "OFFER", null), "SENT", NOW.minusSeconds(1));
        offer(application(requisition, "o3@example.test", NOW, "HIRED", null), "ACCEPTED", NOW.plusSeconds(86_400));
        offer(application(requisition, "o4@example.test", NOW, "OFFER", null), "APPROVED", NOW.plusSeconds(86_400));
        offer(application(otherRequisition, "o5@example.test", NOW, "OFFER", null), "SENT", NOW.plusSeconds(86_400));
        assertThat(value(okJson(DASHBOARD, hrToken), "pendingOffers")).isEqualTo(2);
        assertThat(value(okJson(DASHBOARD, recruiterToken), "pendingOffers")).isEqualTo(1);

        revoke("RECRUITER", "OFFERS_READ_SCOPED");
        JsonNode card = okJson(DASHBOARD, recruiterToken).path("cards").path("pendingOffers");
        assertThat(card.path("available").asBoolean()).isFalse();
        assertThat(card.path("unavailableReason").asText()).isEqualTo("MISSING_OFFERS_READ");
    }

    @Test
    void overdueRequisitionsAreOpenOnesPastTheirNeededByDateMostOverdueFirstAndFullyStaffedLast() throws Exception {
        requisitionState(requisition, "APPROVED", LocalDate.of(2026, 10, 1));
        requisitionState(otherRequisition, "PAUSED", LocalDate.of(2026, 10, 10));
        newRequisition(department, position, "APPROVED", LocalDate.of(2026, 10, 15), 1);   // due today: not late
        newRequisition(department, position, "FILLED", LocalDate.of(2026, 9, 1), 1);       // closed
        newRequisition(department, position, "DRAFT", LocalDate.of(2026, 9, 1), 1);        // never approved
        newRequisition(department, position, "APPROVED", null, 1);                         // no needed-by date
        UUID mostLate = newRequisition(otherDepartment, otherPosition, "APPROVED", LocalDate.of(2026, 9, 15), 2);
        application(mostLate, "hired@example.test", NOW, "HIRED", null);
        application(mostLate, "onboarded@example.test", NOW, "ONBOARDED", null);
        application(mostLate, "screening@example.test", NOW, "SCREENING", null);

        UUID missingOne = newRequisition(otherDepartment, otherPosition, "APPROVED", LocalDate.of(2026, 9, 20), 2);
        application(missingOne, "half@example.test", NOW, "HIRED", null);

        // Requisitions that still miss people come first, most overdue first; the fully staffed one comes last.
        JsonNode overdue = okJson(DASHBOARD, hrToken).path("overdueRequisitions");
        assertThat(overdue.path("totalElements").asLong()).isEqualTo(4);
        assertThat(overdue.path("totalPages").asInt()).isEqualTo(1);
        assertThat(ids(overdue)).containsExactly(missingOne.toString(), requisition.toString(),
                otherRequisition.toString(), mostLate.toString());
        JsonNode top = overdue.path("items").path(0);
        assertThat(top.path("daysOverdue").asLong()).isEqualTo(25);
        assertThat(top.path("neededBy").asText()).isEqualTo("2026-09-20");
        assertThat(top.path("hiredCount").asLong()).isEqualTo(1);
        assertThat(top.path("remainingHeadcount").asLong()).isEqualTo(1);
        assertThat(top.path("fullyStaffed").asBoolean()).isFalse();
        assertThat(top.path("positionName").asText()).isEqualTo("Developer");
        assertThat(overdue.path("items").path(1).path("daysOverdue").asLong()).isEqualTo(14);
        assertThat(overdue.path("items").path(2).path("status").asText()).isEqualTo("PAUSED");
        assertThat(overdue.path("items").path(2).path("daysOverdue").asLong()).isEqualTo(5);
        JsonNode staffed = overdue.path("items").path(3);
        assertThat(staffed.path("daysOverdue").asLong()).isEqualTo(30);
        assertThat(staffed.path("neededBy").asText()).isEqualTo("2026-09-15");
        assertThat(staffed.path("headcount").asInt()).isEqualTo(2);
        assertThat(staffed.path("hiredCount").asLong()).isEqualTo(2);
        assertThat(staffed.path("remainingHeadcount").asLong()).isZero();
        assertThat(staffed.path("fullyStaffed").asBoolean()).isTrue();

        JsonNode second = okJson(DASHBOARD + "?page=1&size=3", hrToken).path("overdueRequisitions");
        assertThat(ids(second)).containsExactly(mostLate.toString());
        assertThat(second.path("totalPages").asInt()).isEqualTo(2);
        assertThat(ids(okJson(DASHBOARD, recruiterToken).path("overdueRequisitions")))
                .containsExactly(requisition.toString());
    }

    @Test
    void aReportThatRunsOutOfTimeAnswers503ReportBusy() throws Exception {
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (var statement = holder.createStatement()) {
                statement.execute("LOCK TABLE job_applications IN ACCESS EXCLUSIVE MODE");
            }
            // The week card waits for the lock until the report's 5 s statement timeout.
            status(get(DASHBOARD, hrToken), 503, "REPORT_BUSY");
            holder.rollback();
        }
        assertThat(value(okJson(DASHBOARD, hrToken), "openRequisitions")).isEqualTo(2);
    }

    @Test
    void permissionsAreCheckedBeforeAnyNumberIsRead() throws Exception {
        assertThat(get(DASHBOARD, null).statusCode()).isEqualTo(401);
        status(get(DASHBOARD, interviewerToken), 403, "FORBIDDEN");
        revoke("RECRUITER", "CANDIDATES_READ_SCOPED");
        status(get(DASHBOARD, recruiterToken), 403, "FORBIDDEN");
        status(get(DASHBOARD + "?size=0", hrToken), 400, "VALIDATION_ERROR");
        status(get(DASHBOARD + "?size=51", hrToken), 400, "VALIDATION_ERROR");
        status(get(DASHBOARD + "?page=-1", hrToken), 400, "VALIDATION_ERROR");
        status(get(DASHBOARD + "?page=abc", hrToken), 400, "VALIDATION_ERROR");
    }

    static long value(JsonNode view, String card) {
        JsonNode node = view.path("cards").path(card);
        assertThat(node.path("available").asBoolean()).as(card + " available").isTrue();
        return node.path("value").asLong();
    }

    static List<String> ids(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.path("items").forEach(item -> ids.add(item.path("requisitionId").asText()));
        return ids;
    }
}
