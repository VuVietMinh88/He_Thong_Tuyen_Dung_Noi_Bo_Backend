package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import vn.ttcs.recruitment.application.ApplicationStage;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Task 580 (story S8-09): applications and pipeline outcomes per candidate source, from the stage history. */
class SourceReportIntegrationTest extends ReportingTestSupport {
    static final String SOURCES = "/api/v1/reports/sources?from=2026-09-25&to=2026-10-15";
    static final Instant DAY = Instant.parse("2026-10-01T03:00:00Z");

    @Test
    void everySourceGetsItsApplicationsAndTheirOutcomesWithAnUnknownBucket() throws Exception {
        UUID website = applications.source("Website");
        UUID referral = applications.source("Giới thiệu");
        // Website: one still new, one rejected after the interview, one hired and later onboarded.
        application(requisition, "w1@example.test", DAY, "NEW", website);
        UUID rejected = application(requisition, "w2@example.test", DAY, "NEW", website);
        path(rejected, DAY, "SCREENING", "INTERVIEW", "REJECTED");
        UUID onboarded = application(requisition, "w3@example.test", DAY, "NEW", website);
        path(onboarded, DAY, "SCREENING", "INTERVIEW", "OFFER", "HIRED");
        applications.stage(onboarded, ApplicationStage.ONBOARDED);
        // Referral: one withdrew during screening, one at offer after HR skipped the interview.
        UUID withdrew = application(requisition, "r1@example.test", DAY, "NEW", referral);
        path(withdrew, DAY, "SCREENING", "WITHDRAWN");
        UUID skipped = application(requisition, "r2@example.test", DAY, "NEW", referral);
        path(skipped, DAY, "SCREENING", "OFFER");
        // A source made inactive later keeps its history.
        jdbc.update("UPDATE recruitment_catalog_items SET active = FALSE WHERE id = ?", referral);
        // first and other have no source (received NOW, inside the cohort).

        JsonNode report = okJson(SOURCES, hrToken);
        assertThat(report.path("cohortSize").asLong()).isEqualTo(7);
        JsonNode site = source(report, "Website");
        assertThat(site.path("sourceId").asText()).isEqualTo(website.toString());
        assertThat(site.path("sourceKey").asText()).isEqualTo(website.toString());
        assertThat(site.path("kind").asText()).isEqualTo("CATALOG");
        assertThat(site.path("active").asBoolean()).isTrue();
        assertThat(site.path("applications").asLong()).isEqualTo(3);
        assertThat(site.path("passedScreening").asLong()).isEqualTo(2);
        assertThat(site.path("hired").asLong()).isEqualTo(1);
        assertThat(site.path("rejected").asLong()).isEqualTo(1);
        assertThat(site.path("inProgress").asLong()).isEqualTo(1);
        JsonNode friends = source(report, "Giới thiệu");
        assertThat(friends.path("active").asBoolean()).isFalse();
        assertThat(friends.path("applications").asLong()).isEqualTo(2);
        assertThat(friends.path("passedScreening").asLong()).isEqualTo(1);    // the skipped interview counts as passed
        assertThat(friends.path("hired").asLong()).isZero();
        assertThat(friends.path("withdrawn").asLong()).isEqualTo(1);
        JsonNode unknown = source(report, "Không rõ nguồn");
        assertThat(unknown.path("sourceId").isNull()).isTrue();
        assertThat(unknown.path("active").isNull()).isTrue();
        assertThat(unknown.path("applications").asLong()).isEqualTo(2);
        assertThat(report.path("sources").path(0).path("name").asText()).isEqualTo("Website");    // most applications

        // The funnel of the same cohort adds up to the same totals.
        JsonNode funnel = okJson("/api/v1/reports/funnel?from=2026-09-25&to=2026-10-15", hrToken);
        assertThat(funnel.path("cohortSize").asLong()).isEqualTo(report.path("cohortSize").asLong());
        assertThat(funnel.path("hired").asLong()).isEqualTo(1);
        assertThat(funnel.path("stages").path(2).path("reached").asLong()).isEqualTo(3);
    }

    @Test
    void referredApplicationsFormTheInternalReferralSourceNotTheUnknownOne() throws Exception {
        UUID hired = referred(requisition, "ref1@example.test");
        path(hired, NOW.minusSeconds(3600), "SCREENING", "INTERVIEW", "OFFER", "HIRED");
        referred(requisition, "ref2@example.test");
        assertThat(jdbc.queryForObject("SELECT source_id FROM job_applications WHERE id = ?", UUID.class, hired)).isNull();

        JsonNode report = okJson(SOURCES, hrToken);
        JsonNode referrals = source(report, "Giới thiệu nội bộ");
        assertThat(referrals.path("kind").asText()).isEqualTo("INTERNAL_REFERRAL");
        assertThat(referrals.path("sourceKey").asText()).isEqualTo("INTERNAL_REFERRAL");
        assertThat(referrals.path("code").asText()).isEqualTo("INTERNAL_REFERRAL");
        assertThat(referrals.path("sourceId").isNull()).isTrue();
        assertThat(referrals.path("applications").asLong()).isEqualTo(2);
        assertThat(referrals.path("passedScreening").asLong()).isEqualTo(1);
        assertThat(referrals.path("hired").asLong()).isEqualTo(1);
        JsonNode unknown = source(report, "Không rõ nguồn");
        assertThat(unknown.path("kind").asText()).isEqualTo("UNKNOWN");
        assertThat(unknown.path("applications").asLong()).isEqualTo(2);           // first and other only
        assertThat(report.path("cohortSize").asLong()).isEqualTo(4);
    }

    @Test
    void sourcesFollowTheViewersScope() throws Exception {
        UUID website = applications.source("Website");
        application(requisition, "w1@example.test", DAY, "NEW", website);
        application(otherRequisition, "w2@example.test", DAY, "NEW", website);
        assertThat(source(okJson(SOURCES, hrToken), "Website").path("applications").asLong()).isEqualTo(2);
        JsonNode recruiterView = okJson(SOURCES, recruiterToken);
        assertThat(recruiterView.path("scope").asText()).isEqualTo("SCOPED");
        assertThat(source(recruiterView, "Website").path("applications").asLong()).isEqualTo(1);
        assertThat(recruiterView.path("cohortSize").asLong()).isEqualTo(2);
        JsonNode approverView = okJson(SOURCES, approverToken);
        assertThat(approverView.path("cohortSize").asLong()).isZero();
        assertThat(approverView.path("sources")).isEmpty();
        status(get(SOURCES, interviewerToken), 403, "FORBIDDEN");
        assertThat(get(SOURCES, null).statusCode()).isEqualTo(401);
    }

    static JsonNode source(JsonNode report, String name) {
        for (JsonNode source : report.path("sources")) {
            if (source.path("name").asText().equals(name)) {
                return source;
            }
        }
        throw new AssertionError("No source " + name + " in " + report);
    }
}
