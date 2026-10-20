package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.ttcs.recruitment.auth.SourceReportIntegrationTest.source;

/** Task 581 (story S8-09): source rates and the quality ranking, filters and the permission scope. */
class SourceQualityIntegrationTest extends ReportingTestSupport {
    static final String SOURCES = "/api/v1/reports/sources?from=2026-09-25&to=2026-10-15";
    static final Instant DAY = Instant.parse("2026-10-01T03:00:00Z");
    UUID portal, referral, fair;

    /** count applications of the source on the requisition, the first hired ones hired, the next passed ones screened. */
    void bring(UUID requisitionId, UUID sourceId, String prefix, int count, int hired, int passed) {
        for (int index = 0; index < count; index++) {
            UUID application = application(requisitionId, prefix + index + "@example.test", DAY, "NEW", sourceId);
            if (index < hired) {
                path(application, DAY, "SCREENING", "INTERVIEW", "OFFER", "HIRED");
            } else if (index < hired + passed) {
                path(application, DAY, "SCREENING", "INTERVIEW", "REJECTED");
            } else {
                path(application, DAY, "SCREENING", "REJECTED");
            }
        }
    }

    void arrange() {
        portal = applications.source("Cổng việc làm");
        referral = applications.source("Giới thiệu nội bộ");
        fair = applications.source("Hội chợ việc làm");
        bring(requisition, portal, "p", 12, 1, 2);       // most applications, 8 % hired
        bring(requisition, referral, "r", 5, 2, 1);      // 40 % hired: the best
        bring(otherRequisition, fair, "f", 2, 2, 0);     // 100 % hired, but only two applications
    }

    @Test
    void theBestSourceIsChosenByOutcomesOnAMeaningfulSampleNotByVolume() throws Exception {
        arrange();
        JsonNode report = okJson(SOURCES, hrToken);
        assertThat(report.path("minApplications").asInt()).isEqualTo(5);
        assertThat(report.path("rankingRule").asText()).isEqualTo("HIRE_RATE_THEN_SCREENING_PASS_RATE_THEN_APPLICATIONS_MIN_SAMPLE");
        assertThat(report.path("bestSource").path("name").asText()).isEqualTo("Giới thiệu nội bộ");
        assertThat(report.path("mostApplications").path("name").asText()).isEqualTo("Cổng việc làm");
        // Ranked sources first by rank, then the others by applications (two each here), then by name.
        assertThat(names(report)).containsExactly("Giới thiệu nội bộ", "Cổng việc làm", "Hội chợ việc làm", "Không rõ nguồn");
        JsonNode best = source(report, "Giới thiệu nội bộ");
        assertThat(best.path("qualityRank").asInt()).isEqualTo(1);
        assertThat(best.path("hireRate").decimalValue()).isEqualByComparingTo("0.4000");
        assertThat(best.path("screeningPassRate").decimalValue()).isEqualByComparingTo("0.6000");
        assertThat(best.path("meetsMinimumSample").asBoolean()).isTrue();
        JsonNode large = source(report, "Cổng việc làm");
        assertThat(large.path("qualityRank").asInt()).isEqualTo(2);
        assertThat(large.path("hireRate").decimalValue()).isEqualByComparingTo("0.0833");
        JsonNode small = source(report, "Hội chợ việc làm");
        assertThat(small.path("qualityRank").isNull()).isTrue();
        assertThat(small.path("meetsMinimumSample").asBoolean()).isFalse();
        assertThat(small.path("hireRate").decimalValue()).isEqualByComparingTo("1.0000");
        JsonNode unknown = source(report, "Không rõ nguồn");
        assertThat(unknown.path("qualityRank").isNull()).isTrue();
        assertThat(unknown.path("hireRate").decimalValue()).isEqualByComparingTo("0.0000");

        // A smaller minimum lets the small source in: then its 100 % puts it first.
        JsonNode lenient = okJson(SOURCES + "&minApplications=2", hrToken);
        assertThat(lenient.path("bestSource").path("name").asText()).isEqualTo("Hội chợ việc làm");
        assertThat(okJson(SOURCES + "&minApplications=50", hrToken).path("bestSource").isNull()).isTrue();
        status(get(SOURCES + "&minApplications=0", hrToken), 400, "VALIDATION_ERROR");
        status(get(SOURCES + "&minApplications=1001", hrToken), 400, "VALIDATION_ERROR");
    }

    @Test
    void internalReferralsAreRankedLikeACatalogSource() throws Exception {
        UUID portal = applications.source("Cổng việc làm");
        bring(requisition, portal, "p", 10, 1, 1);                       // 10 % hired
        for (int index = 0; index < 5; index++) {                         // five referrals through the API, 2 hired
            UUID application = referred(requisition, "ref" + index + "@example.test");
            if (index < 2) {
                path(application, NOW.minusSeconds(3600), "SCREENING", "INTERVIEW", "OFFER", "HIRED");
            }
        }
        JsonNode report = okJson(SOURCES, hrToken);
        assertThat(report.path("bestSource").path("kind").asText()).isEqualTo("INTERNAL_REFERRAL");
        assertThat(report.path("bestSource").path("name").asText()).isEqualTo("Giới thiệu nội bộ");
        JsonNode referrals = source(report, "Giới thiệu nội bộ");
        assertThat(referrals.path("qualityRank").asInt()).isEqualTo(1);
        assertThat(referrals.path("hireRate").decimalValue()).isEqualByComparingTo("0.4000");
        assertThat(source(report, "Cổng việc làm").path("qualityRank").asInt()).isEqualTo(2);
        assertThat(report.path("mostApplications").path("name").asText()).isEqualTo("Cổng việc làm");
        assertThat(source(report, "Không rõ nguồn").path("qualityRank").isNull()).isTrue();
    }

    @Test
    void filtersAndTheScopeNarrowTheSourcesNumbers() throws Exception {
        arrange();
        JsonNode first = okJson(SOURCES + "&requisitionId=" + requisition, hrToken);
        assertThat(first.path("filters").path("requisitionId").asText()).isEqualTo(requisition.toString());
        assertThat(names(first)).doesNotContain("Hội chợ việc làm");
        assertThat(okJson(SOURCES + "&departmentId=" + otherDepartment, hrToken).path("cohortSize").asLong()).isEqualTo(3);
        assertThat(okJson(SOURCES + "&positionId=" + position, hrToken).path("cohortSize").asLong()).isEqualTo(18);

        JsonNode recruiterView = okJson(SOURCES, recruiterToken);
        assertThat(names(recruiterView)).doesNotContain("Hội chợ việc làm");
        assertThat(recruiterView.path("cohortSize").asLong()).isEqualTo(18);
        JsonNode outsiderView = okJson(SOURCES, outsiderToken);
        assertThat(outsiderView.path("cohortSize").asLong()).isEqualTo(3);
        assertThat(outsiderView.path("bestSource").isNull()).isTrue();
        status(get(SOURCES + "&requisitionId=" + otherRequisition, recruiterToken), 403, "FORBIDDEN");
        status(get(SOURCES + "&departmentId=" + UUID.randomUUID(), hrToken), 404, "DEPARTMENT_NOT_FOUND");
        revoke("RECRUITER", "CANDIDATES_READ_SCOPED");
        status(get(SOURCES, recruiterToken), 403, "FORBIDDEN");
    }

    static List<String> names(JsonNode report) {
        List<String> names = new ArrayList<>();
        report.path("sources").forEach(source -> names.add(source.path("name").asText()));
        return names;
    }
}
