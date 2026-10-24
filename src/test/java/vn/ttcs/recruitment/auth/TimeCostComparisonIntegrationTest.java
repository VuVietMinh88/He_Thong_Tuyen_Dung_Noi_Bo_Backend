package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Task 575 (story S8-08): time and cost compared by department and by position, filtered and scoped. */
class TimeCostComparisonIntegrationTest extends ReportingTestSupport {
    static final String REPORT = "/api/v1/reports/time-cost?from=2026-09-01&to=2026-09-30";
    static final Instant APPROVED = Instant.parse("2026-09-01T03:00:00Z");
    UUID third;

    void arrange() {
        jdbc.update("UPDATE departments SET name = 'Kỹ thuật' WHERE id = ?", department);
        jdbc.update("UPDATE departments SET name = 'Tài chính' WHERE id = ?", otherDepartment);
        jdbc.update("UPDATE positions SET name = 'Lập trình viên' WHERE id = ?", position);
        jdbc.update("UPDATE positions SET name = 'Kế toán' WHERE id = ?", otherPosition);
        third = newRequisition(otherDepartment, position, "APPROVED", null, 1);
        for (UUID id : List.of(requisition, otherRequisition, third)) {
            workflowEvent(id, "APPROVED", "PENDING_APPROVAL", "APPROVED", APPROVED);
        }
        hire(requisition, "a@example.test", "2026-09-11");     // 10 days
        hire(requisition, "b@example.test", "2026-09-21");     // 20 days
        hire(otherRequisition, "c@example.test", "2026-09-06"); // 5 days
        hire(third, "d@example.test", "2026-09-30");            // 29 days
        cost(requisition, "ADVERTISING", 4_000_000);
        cost(otherRequisition, "REFERRAL_BONUS", 3_000_000);
    }

    void hire(UUID requisitionId, String email, String date) {
        Instant at = LocalDate.parse(date).atTime(10, 0).atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toInstant();
        UUID application = application(requisitionId, email, at.minusSeconds(86_400L * 20), "NEW", null);
        stageEvent(application, "OFFER", "HIRED", at);
        jdbc.update("UPDATE job_applications SET stage = 'HIRED' WHERE id = ?", application);
    }

    void cost(UUID requisitionId, String type, long amount) {
        jdbc.update("""
                INSERT INTO recruitment_costs(id,requisition_id,cost_type,amount_vnd,incurred_on,recorded_by,recorded_at)
                VALUES (?,?,?,?,?,?,?)
                """, UUID.randomUUID(), requisitionId, type, amount, Date.valueOf("2026-09-15"), hr, Timestamp.from(NOW));
    }

    @Test
    void departmentsAndPositionsAreComparedFromTheirOwnHiresAndCosts() throws Exception {
        arrange();
        JsonNode report = okJson(REPORT, hrToken);
        assertThat(names(report.path("byDepartment"))).containsExactly("Kỹ thuật", "Tài chính");
        JsonNode engineering = report.path("byDepartment").path(0);
        assertThat(engineering.path("id").asText()).isEqualTo(department.toString());
        assertThat(engineering.path("requisitions").asLong()).isEqualTo(1);
        assertThat(engineering.path("metrics").path("hires").asLong()).isEqualTo(2);
        assertThat(engineering.path("metrics").path("timeToHire").path("averageDays").decimalValue()).isEqualByComparingTo("15.0");
        assertThat(engineering.path("metrics").path("costPerHireVnd").asLong()).isEqualTo(2_000_000);
        JsonNode finance = report.path("byDepartment").path(1);
        assertThat(finance.path("requisitions").asLong()).isEqualTo(2);
        assertThat(finance.path("metrics").path("hires").asLong()).isEqualTo(2);
        assertThat(finance.path("metrics").path("timeToHire").path("averageDays").decimalValue()).isEqualByComparingTo("17.0");
        assertThat(finance.path("metrics").path("costPerHireVnd").asLong()).isEqualTo(1_500_000);

        assertThat(names(report.path("byPosition"))).containsExactly("Kế toán", "Lập trình viên");
        JsonNode developer = report.path("byPosition").path(1);
        assertThat(developer.path("requisitions").asLong()).isEqualTo(2);
        assertThat(developer.path("metrics").path("hires").asLong()).isEqualTo(3);
        // (10 + 20 + 29) / 3 = 19.67 days, weighted by hires.
        assertThat(developer.path("metrics").path("timeToHire").path("averageDays").decimalValue()).isEqualByComparingTo("19.7");
        assertThat(developer.path("metrics").path("costs").path("totalVnd").asLong()).isEqualTo(4_000_000);
        assertThat(developer.path("metrics").path("costPerHireVnd").asLong()).isEqualTo(1_333_333);
        assertThat(report.path("byPosition").path(0).path("metrics").path("costPerHireVnd").asLong()).isEqualTo(3_000_000);
        assertThat(report.path("overall").path("hires").asLong()).isEqualTo(4);
        assertThat(report.path("overall").path("costPerHireVnd").asLong()).isEqualTo(1_750_000);
    }

    @Test
    void filtersNarrowEveryPartAndStayInsideTheScope() throws Exception {
        arrange();
        JsonNode finance = okJson(REPORT + "&departmentId=" + otherDepartment, hrToken);
        assertThat(finance.path("filters").path("departmentId").asText()).isEqualTo(otherDepartment.toString());
        assertThat(finance.path("overall").path("hires").asLong()).isEqualTo(2);
        assertThat(names(finance.path("byDepartment"))).containsExactly("Tài chính");
        assertThat(names(finance.path("byPosition"))).containsExactly("Kế toán", "Lập trình viên");
        assertThat(finance.path("requisitions")).hasSize(2);
        assertThat(okJson(REPORT + "&positionId=" + position, hrToken).path("overall").path("hires").asLong()).isEqualTo(3);
        assertThat(okJson(REPORT + "&requisitionId=" + third, hrToken).path("overall").path("hires").asLong()).isEqualTo(1);

        JsonNode recruiterView = okJson(REPORT, recruiterToken);
        assertThat(names(recruiterView.path("byDepartment"))).containsExactly("Kỹ thuật");
        assertThat(recruiterView.path("overall").path("costs").path("totalVnd").asLong()).isEqualTo(4_000_000);
        status(get(REPORT + "&departmentId=" + otherDepartment, recruiterToken), 403, "FORBIDDEN");
        status(get(REPORT + "&requisitionId=" + third, recruiterToken), 403, "FORBIDDEN");
        status(get(REPORT + "&positionId=" + UUID.randomUUID(), hrToken), 404, "POSITION_NOT_FOUND");
        status(get(REPORT + "&requisitionId=" + UUID.randomUUID(), hrToken), 404, "REQUISITION_NOT_FOUND");
    }

    static List<String> names(JsonNode groups) {
        List<String> names = new ArrayList<>();
        groups.forEach(group -> names.add(group.path("name").asText()));
        return names;
    }
}
