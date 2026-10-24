package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import vn.ttcs.recruitment.application.ApplicationStage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Task 567 (story S8-07): the cohort funnel from the stage history, through HTTP on a temporary database. */
class FunnelReportIntegrationTest extends ReportingTestSupport {
    static final String FUNNEL = "/api/v1/reports/funnel";
    static final Instant DAY = Instant.parse("2026-10-01T03:00:00Z");

    @Test
    void stagesCountTheFurthestStageInTheHistoryOncePerApplication() throws Exception {
        received(first.applicationId(), DAY);                                       // NEW, nothing happened
        received(other.applicationId(), Instant.parse("2026-09-20T03:00:00Z"));     // before the cohort
        UUID rejectedAfterInterview = application(requisition, "a@example.test", DAY, "NEW", null);
        path(rejectedAfterInterview, DAY, "SCREENING", "INTERVIEW", "REJECTED");
        UUID withdrew = application(requisition, "b@example.test", DAY, "NEW", null);
        path(withdrew, DAY, "SCREENING", "WITHDRAWN");
        UUID hired = application(requisition, "c@example.test", DAY, "NEW", null);
        path(hired, DAY, "SCREENING", "INTERVIEW", "OFFER", "HIRED");
        UUID onboarded = application(requisition, "d@example.test", DAY, "NEW", null);
        path(onboarded, DAY, "SCREENING", "INTERVIEW", "OFFER", "HIRED");
        applications.stage(onboarded, ApplicationStage.ONBOARDED);
        UUID skipped = application(requisition, "e@example.test", DAY, "NEW", null);
        path(skipped, DAY, "SCREENING", "OFFER");
        // Reopened: moved back to NEW and screened again; still one application at SCREENING.
        UUID reopened = application(requisition, "f@example.test", DAY, "NEW", null);
        stageEvent(reopened, "NEW", "SCREENING", DAY.plusSeconds(60));
        stageEvent(reopened, "SCREENING", "NEW", DAY.plusSeconds(120));
        stageEvent(reopened, "NEW", "SCREENING", DAY.plusSeconds(180));
        jdbc.update("UPDATE job_applications SET stage = 'SCREENING' WHERE id = ?", reopened);
        // Rejected straight from NEW without any history row (an imported record): it still reached NEW.
        application(requisition, "g@example.test", DAY, "REJECTED", null);

        JsonNode funnel = okJson(FUNNEL + "?from=2026-09-25&to=2026-10-15", hrToken);
        assertThat(funnel.path("from").asText()).isEqualTo("2026-09-25");
        assertThat(funnel.path("cohortSize").asLong()).isEqualTo(8);
        assertThat(funnel.path("hired").asLong()).isEqualTo(2);
        assertThat(funnel.path("overallConversionRate").decimalValue()).isEqualByComparingTo("0.2500");
        assertThat(funnel.path("interviewSkipped").asLong()).isEqualTo(1);
        assertThat(values(funnel, "stage")).containsExactly("NEW", "SCREENING", "INTERVIEW", "OFFER", "HIRED", "ONBOARDED");
        assertThat(values(funnel, "reached")).containsExactly("8", "6", "4", "3", "2", "1");
        assertThat(values(funnel, "advanced")).containsExactly("6", "4", "3", "2", "1", "0");
        JsonNode created = funnel.path("stages").path(0);
        assertThat(created.path("conversionRate").decimalValue()).isEqualByComparingTo("0.7500");
        assertThat(created.path("rejected").asLong()).isEqualTo(1);
        assertThat(created.path("inProgress").asLong()).isEqualTo(1);
        JsonNode screening = funnel.path("stages").path(1);
        assertThat(screening.path("withdrawn").asLong()).isEqualTo(1);
        assertThat(screening.path("inProgress").asLong()).isEqualTo(1);
        assertThat(screening.path("dropRate").decimalValue()).isEqualByComparingTo("0.1667");
        assertThat(funnel.path("stages").path(2).path("rejected").asLong()).isEqualTo(1);
        assertThat(funnel.path("stages").path(5).path("nextStage").isNull()).isTrue();
        assertThat(funnel.path("stages").path(5).path("conversionRate").isNull()).isTrue();

        // The recruiter assigned to the requisition sees the same cohort; the other recruiter sees none of it.
        assertThat(okJson(FUNNEL + "?from=2026-09-25&to=2026-10-15", recruiterToken).path("cohortSize").asLong())
                .isEqualTo(8);
        JsonNode empty = okJson(FUNNEL + "?from=2026-09-25&to=2026-10-15", outsiderToken);
        assertThat(empty.path("cohortSize").asLong()).isZero();
        assertThat(empty.path("overallConversionRate").isNull()).isTrue();
        assertThat(empty.path("stages").path(0).path("conversionRate").isNull()).isTrue();
        assertThat(empty.path("stages").path(0).path("dropRate").isNull()).isTrue();
    }

    @Test
    void theCohortIsChosenByBusinessDatesAndDefaultsToNinetyDays() throws Exception {
        received(first.applicationId(), Instant.parse("2026-09-30T16:59:59Z"));     // 30 Sep 23:59:59 in Vietnam
        received(other.applicationId(), Instant.parse("2026-09-30T17:00:00Z"));     // 1 Oct 00:00
        assertThat(okJson(FUNNEL + "?from=2026-10-01&to=2026-10-01", hrToken).path("cohortSize").asLong()).isEqualTo(1);
        assertThat(okJson(FUNNEL + "?from=2026-09-30&to=2026-09-30", hrToken).path("cohortSize").asLong()).isEqualTo(1);
        JsonNode defaults = okJson(FUNNEL, hrToken);
        assertThat(defaults.path("from").asText()).isEqualTo("2026-07-18");
        assertThat(defaults.path("to").asText()).isEqualTo("2026-10-15");
        assertThat(defaults.path("cohortSize").asLong()).isEqualTo(2);
        status(get(FUNNEL + "?from=2026-10-01", hrToken), 400, "VALIDATION_ERROR");
        status(get(FUNNEL, interviewerToken), 403, "FORBIDDEN");
        assertThat(get(FUNNEL, null).statusCode()).isEqualTo(401);
    }

    static List<String> values(JsonNode funnel, String field) {
        List<String> values = new ArrayList<>();
        funnel.path("stages").forEach(stage -> values.add(stage.path(field).asText()));
        return values;
    }
}
