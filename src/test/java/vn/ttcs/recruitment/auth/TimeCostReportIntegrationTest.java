package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import vn.ttcs.recruitment.application.ApplicationStage;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Task 574 (story S8-08): time to hire and cost per hire from real hire evidence and entered costs. */
class TimeCostReportIntegrationTest extends ReportingTestSupport {
    static final String REPORT = "/api/v1/reports/time-cost?from=2026-09-01&to=2026-09-30";
    static final Instant APPROVED = Instant.parse("2026-09-01T03:00:00Z");

    /** An application of the requisition that entered HIRED at hiredAt (after OFFER), by HR. */
    UUID hire(UUID requisitionId, String email, Instant hiredAt) {
        UUID application = application(requisitionId, email, hiredAt.minusSeconds(30 * 86_400L), "NEW", null);
        stageEvent(application, "OFFER", "HIRED", hiredAt);
        jdbc.update("UPDATE job_applications SET stage = 'HIRED' WHERE id = ?", application);
        return application;
    }

    void cost(UUID requisitionId, String type, long amount, String date, boolean voided) {
        jdbc.update("""
                INSERT INTO recruitment_costs(id,requisition_id,cost_type,amount_vnd,incurred_on,recorded_by,recorded_at,
                    voided_at,voided_by,void_reason)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, UUID.randomUUID(), requisitionId, type, amount, Date.valueOf(LocalDate.parse(date)), hr,
                Timestamp.from(NOW), voided ? Timestamp.from(NOW) : null, voided ? hr : null, voided ? "Sai" : null);
    }

    @Test
    void timeToHireRunsFromTheFinalApprovalToTheHireAndCostPerHireDividesThePeriodsCosts() throws Exception {
        // An intermediate level approved first; the final approval made the requisition APPROVED on 1 Sep.
        workflowEvent(requisition, "APPROVED", "PENDING_APPROVAL", "PENDING_APPROVAL", APPROVED.minusSeconds(5 * 86_400L));
        workflowEvent(requisition, "APPROVED", "PENDING_APPROVAL", "APPROVED", APPROVED);
        workflowEvent(requisition, "PAUSED", "APPROVED", "PAUSED", APPROVED.plusSeconds(2 * 86_400L));
        workflowEvent(requisition, "RESUMED", "PAUSED", "APPROVED", APPROVED.plusSeconds(3 * 86_400L));
        UUID ten = hire(requisition, "ten@example.test", Instant.parse("2026-09-11T03:00:00Z"));
        // 17:30 UTC on 20 Sep is 00:30 on 21 Sep in Vietnam: 20 days.
        hire(requisition, "twenty@example.test", Instant.parse("2026-09-20T17:30:00Z"));
        hire(requisition, "october@example.test", Instant.parse("2026-10-01T03:00:00Z"));          // after the period
        offer(ten, "ACCEPTED", NOW.plusSeconds(86_400));
        applications.stage(ten, ApplicationStage.ONBOARDED);
        cost(requisition, "ADVERTISING", 3_000_000, "2026-09-05", false);
        cost(requisition, "REFERRAL_BONUS", 1_000_000, "2026-09-25", false);
        cost(requisition, "ADVERTISING", 9_000_000, "2026-09-06", true);                           // voided
        cost(requisition, "ADVERTISING", 8_000_000, "2026-08-31", false);                          // before the period

        JsonNode report = okJson(REPORT, hrToken);
        JsonNode row = requisitionRow(report, requisition);
        assertThat(row.path("approvedOn").asText()).isEqualTo("2026-09-01");
        JsonNode metrics = row.path("metrics");
        assertThat(metrics.path("hires").asLong()).isEqualTo(2);
        assertThat(metrics.path("hiresWithAcceptedOffer").asLong()).isEqualTo(1);
        assertThat(metrics.path("hiresOnboarded").asLong()).isEqualTo(1);
        assertThat(metrics.path("timeToHire").path("sampleSize").asLong()).isEqualTo(2);
        assertThat(metrics.path("timeToHire").path("averageDays").decimalValue()).isEqualByComparingTo("15.0");
        assertThat(metrics.path("timeToHire").path("minDays").asLong()).isEqualTo(10);
        assertThat(metrics.path("timeToHire").path("maxDays").asLong()).isEqualTo(20);
        assertThat(metrics.path("costs").path("advertisingVnd").asLong()).isEqualTo(3_000_000);
        assertThat(metrics.path("costs").path("referralBonusVnd").asLong()).isEqualTo(1_000_000);
        assertThat(metrics.path("costs").path("totalVnd").asLong()).isEqualTo(4_000_000);
        assertThat(metrics.path("costs").path("entries").asLong()).isEqualTo(2);
        assertThat(metrics.path("costsRecorded").asBoolean()).isTrue();
        assertThat(metrics.path("costPerHireVnd").asLong()).isEqualTo(2_000_000);
    }

    @Test
    void missingApprovalsNoHiresAndTheOverallAverageAreHandledHonestly() throws Exception {
        workflowEvent(requisition, "APPROVED", "PENDING_APPROVAL", "APPROVED", APPROVED);
        hire(requisition, "a@example.test", Instant.parse("2026-09-11T03:00:00Z"));               // 10 days
        hire(requisition, "b@example.test", Instant.parse("2026-09-21T03:00:00Z"));               // 20 days
        UUID slow = newRequisition(department, position, "APPROVED", null, 1);
        workflowEvent(slow, "APPROVED", "PENDING_APPROVAL", "APPROVED", Instant.parse("2026-08-01T03:00:00Z"));
        hire(slow, "c@example.test", Instant.parse("2026-09-30T03:00:00Z"));                      // 60 days
        hire(otherRequisition, "d@example.test", Instant.parse("2026-09-15T03:00:00Z"));          // no approval event
        UUID costOnly = newRequisition(otherDepartment, otherPosition, "APPROVED", null, 1);
        cost(costOnly, "ADVERTISING", 5_000_000, "2026-09-10", false);

        JsonNode report = okJson(REPORT, hrToken);
        assertThat(report.path("from").asText()).isEqualTo("2026-09-01");
        JsonNode overall = report.path("overall");
        assertThat(overall.path("hires").asLong()).isEqualTo(4);
        // Weighted by hires: (10 + 20 + 60) / 3 = 30.0, not the mean of requisition means (15 + 60) / 2 = 37.5.
        assertThat(overall.path("timeToHire").path("averageDays").decimalValue()).isEqualByComparingTo("30.0");
        assertThat(overall.path("timeToHire").path("sampleSize").asLong()).isEqualTo(3);
        assertThat(overall.path("timeToHire").path("missingApproval").asLong()).isEqualTo(1);
        assertThat(overall.path("costs").path("totalVnd").asLong()).isEqualTo(5_000_000);
        assertThat(overall.path("costPerHireVnd").asLong()).isEqualTo(1_250_000);

        JsonNode unapproved = requisitionRow(report, otherRequisition).path("metrics");
        assertThat(requisitionRow(report, otherRequisition).path("approvedOn").isNull()).isTrue();
        assertThat(unapproved.path("timeToHire").path("averageDays").isNull()).isTrue();
        assertThat(unapproved.path("timeToHire").path("missingApproval").asLong()).isEqualTo(1);
        // A hire but no cost recorded in the period: no cost per hire (null, not 0).
        assertThat(unapproved.path("costsRecorded").asBoolean()).isFalse();
        assertThat(unapproved.path("costPerHireVnd").isNull()).isTrue();
        JsonNode noHire = requisitionRow(report, costOnly).path("metrics");
        assertThat(noHire.path("hires").asLong()).isZero();
        assertThat(noHire.path("costs").path("totalVnd").asLong()).isEqualTo(5_000_000);
        assertThat(noHire.path("costsRecorded").asBoolean()).isTrue();
        assertThat(noHire.path("costPerHireVnd").isNull()).isTrue();
        assertThat(report.path("requisitions")).hasSize(4);

        // The recruiter of the first requisition sees only its numbers.
        JsonNode scoped = okJson(REPORT, recruiterToken);
        assertThat(scoped.path("scope").asText()).isEqualTo("SCOPED");
        assertThat(scoped.path("overall").path("hires").asLong()).isEqualTo(2);
        assertThat(scoped.path("requisitions")).hasSize(1);

        JsonNode empty = okJson("/api/v1/reports/time-cost?from=2026-01-01&to=2026-01-31", hrToken);
        assertThat(empty.path("overall").path("hires").asLong()).isZero();
        assertThat(empty.path("overall").path("timeToHire").path("averageDays").isNull()).isTrue();
        assertThat(empty.path("overall").path("costPerHireVnd").isNull()).isTrue();
        assertThat(empty.path("requisitions")).isEmpty();
        status(get("/api/v1/reports/time-cost", interviewerToken), 403, "FORBIDDEN");
        status(get("/api/v1/reports/time-cost?from=2026-01-01", hrToken), 400, "VALIDATION_ERROR");
    }

    static JsonNode requisitionRow(JsonNode report, UUID requisitionId) {
        for (JsonNode row : report.path("requisitions")) {
            if (row.path("requisitionId").asText().equals(requisitionId.toString())) {
                return row;
            }
        }
        throw new AssertionError("No row for requisition " + requisitionId + " in " + report);
    }
}
