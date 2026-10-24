package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Task 568 (story S8-07): the funnel's position, department and period filters inside the live permission scope. */
class FunnelScopeIntegrationTest extends ReportingTestSupport {
    static final String FUNNEL = "/api/v1/reports/funnel?from=2026-09-25&to=2026-10-15";
    static final Instant DAY = Instant.parse("2026-10-01T03:00:00Z");

    @Test
    void requisitionDepartmentAndPositionFiltersNarrowTheCohort() throws Exception {
        UUID screened = application(requisition, "a@example.test", DAY, "NEW", null);
        path(screened, DAY, "SCREENING");
        UUID samePosition = newRequisition(otherDepartment, position, "APPROVED", null, 1);
        application(samePosition, "b@example.test", DAY, "NEW", null);

        JsonNode all = okJson(FUNNEL, hrToken);
        assertThat(all.path("cohortSize").asLong()).isEqualTo(4);
        assertThat(all.path("filters").path("requisitionId").isNull()).isTrue();
        JsonNode byRequisition = okJson(FUNNEL + "&requisitionId=" + requisition, hrToken);
        assertThat(byRequisition.path("cohortSize").asLong()).isEqualTo(2);
        assertThat(byRequisition.path("filters").path("requisitionId").asText()).isEqualTo(requisition.toString());
        assertThat(byRequisition.path("stages").path(1).path("reached").asLong()).isEqualTo(1);
        assertThat(okJson(FUNNEL + "&departmentId=" + otherDepartment, hrToken).path("cohortSize").asLong()).isEqualTo(2);
        assertThat(okJson(FUNNEL + "&positionId=" + position, hrToken).path("cohortSize").asLong()).isEqualTo(3);
        assertThat(okJson(FUNNEL + "&positionId=" + position + "&departmentId=" + department, hrToken)
                .path("cohortSize").asLong()).isEqualTo(2);
        // A filter narrows the recruiter's own scope; it never adds a requisition of another recruiter.
        assertThat(okJson(FUNNEL, recruiterToken).path("cohortSize").asLong()).isEqualTo(2);
        assertThat(okJson(FUNNEL + "&positionId=" + position, recruiterToken).path("cohortSize").asLong()).isEqualTo(2);
    }

    @Test
    void filterTargetsMustExistAndBeInsideTheScope() throws Exception {
        status(get(FUNNEL + "&requisitionId=" + UUID.randomUUID(), hrToken), 404, "REQUISITION_NOT_FOUND");
        status(get(FUNNEL + "&departmentId=" + UUID.randomUUID(), hrToken), 404, "DEPARTMENT_NOT_FOUND");
        status(get(FUNNEL + "&positionId=" + UUID.randomUUID(), hrToken), 404, "POSITION_NOT_FOUND");
        status(get(FUNNEL + "&requisitionId=" + otherRequisition, recruiterToken), 403, "FORBIDDEN");
        status(get(FUNNEL + "&departmentId=" + otherDepartment, recruiterToken), 403, "FORBIDDEN");
        status(get(FUNNEL + "&positionId=" + otherPosition, recruiterToken), 403, "FORBIDDEN");
        status(get(FUNNEL + "&requisitionId=" + requisition, interviewerToken), 403, "FORBIDDEN");
        status(get(FUNNEL + "&requisitionId=bad", hrToken), 400, "VALIDATION_ERROR");
        assertThat(okJson(FUNNEL + "&requisitionId=" + requisition, managerToken).path("cohortSize").asLong()).isEqualTo(1);
    }

    @Test
    void theScopeIsReadAgainOnEveryRequest() throws Exception {
        assertThat(okJson(FUNNEL, managerToken).path("cohortSize").asLong()).isEqualTo(1);
        UUID successor = applications.staff("Trưởng phòng mới", "successor@example.test");
        jdbc.update("UPDATE departments SET manager_user_id = ? WHERE id = ?", successor, department);
        assertThat(okJson(FUNNEL, managerToken).path("cohortSize").asLong()).isZero();

        assertThat(okJson(FUNNEL, outsiderToken).path("cohortSize").asLong()).isEqualTo(1);
        jdbc.update("DELETE FROM requisition_recruiters WHERE recruiter_id = ?", outsider);
        assertThat(okJson(FUNNEL, outsiderToken).path("cohortSize").asLong()).isZero();

        JsonNode approver = okJson(FUNNEL, approverToken);
        assertThat(approver.path("scope").asText()).isEqualTo("SCOPED");
        assertThat(approver.path("cohortSize").asLong()).isZero();

        revoke("HR_MANAGER", "CANDIDATES_READ_ALL");
        grant("HR_MANAGER", "CANDIDATES_READ_SCOPED");
        assertThat(okJson(FUNNEL, hrToken).path("cohortSize").asLong()).isZero();
        revoke("HR_MANAGER", "REPORTS_READ_ALL");
        status(get(FUNNEL, hrToken), 403, "FORBIDDEN");
    }
}
