package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Task 418: positive, bounded SLA days per active stage, changed only by CANDIDATES_WRITE_ALL with a version check. */
class SlaThresholdApiIntegrationTest extends ScreeningTestSupport {
    private static final String THRESHOLDS = "/api/v1/sla/thresholds";

    @BeforeEach void reset() {
        jdbc.update("UPDATE stage_sla_thresholds SET max_days = NULL, version = 0, updated_by = NULL");
    }

    @Test
    void readersSeeTheFourActiveStagesAndHrSetsAndClearsADayLimit() throws Exception {
        JsonNode list = ok(call("GET", THRESHOLDS, null, recruiterToken));
        assertThat(list).hasSize(4);
        assertThat(list.path(0).path("stage").asText()).isEqualTo("NEW");
        assertThat(list.path(0).path("stageName").asText()).isEqualTo("Hồ sơ mới");
        assertThat(list.path(0).path("maxDays").isNull()).isTrue();

        JsonNode saved = ok(call("PUT", THRESHOLDS + "/NEW", "{\"expectedVersion\":0,\"maxDays\":3}", hrToken));
        assertThat(saved.path("maxDays").asInt()).isEqualTo(3);
        assertThat(saved.path("version").asLong()).isEqualTo(1);
        assertThat(saved.path("updatedByName").asText()).isEqualTo("HR Nội Bộ");
        var conflict = call("PUT", THRESHOLDS + "/NEW", "{\"expectedVersion\":0,\"maxDays\":5}", hrToken);
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(json.readTree(conflict.body()).path("code").asText()).isEqualTo("SLA_THRESHOLD_VERSION_CONFLICT");
        var body = new LinkedHashMap<String, Object>();
        body.put("expectedVersion", 1);
        body.put("maxDays", null);
        assertThat(ok(call("PUT", THRESHOLDS + "/NEW", json.writeValueAsString(body), hrToken)).path("maxDays").isNull()).isTrue();
        // A recruiter reads but does not configure.
        assertThat(call("PUT", THRESHOLDS + "/NEW", "{\"expectedVersion\":2,\"maxDays\":1}", recruiterToken).statusCode())
                .isEqualTo(403);
    }

    @Test
    void daysArePositiveBoundedWholeNumbersForActiveStagesOnly() throws Exception {
        for (String invalid : new String[] {"{\"expectedVersion\":0,\"maxDays\":0}", "{\"expectedVersion\":0,\"maxDays\":366}",
                "{\"expectedVersion\":0,\"maxDays\":-1}", "{\"maxDays\":3}"}) {
            var response = call("PUT", THRESHOLDS + "/SCREENING", invalid, hrToken);
            assertThat(response.statusCode()).as(invalid).isEqualTo(400);
            assertThat(json.readTree(response.body()).path("code").asText()).as(invalid).isEqualTo("VALIDATION_ERROR");
        }
        // A save without maxDays never clears the stage's SLA by omission: clearing takes an explicit null.
        ok(call("PUT", THRESHOLDS + "/SCREENING", "{\"expectedVersion\":0,\"maxDays\":4}", hrToken));
        var missing = call("PUT", THRESHOLDS + "/SCREENING", "{\"expectedVersion\":1}", hrToken);
        assertThat(missing.statusCode()).isEqualTo(400);
        JsonNode error = json.readTree(missing.body());
        assertThat(error.path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(error.path("fieldErrors").has("maxDays")).isTrue();
        assertThat(jdbc.queryForObject("SELECT max_days FROM stage_sla_thresholds WHERE stage='SCREENING'", Integer.class))
                .isEqualTo(4);
        jdbc.update("UPDATE stage_sla_thresholds SET max_days = NULL, version = 0 WHERE stage = 'SCREENING'");
        for (String malformed : new String[] {"{\"expectedVersion\":0,\"maxDays\":2.5}", "{\"expectedVersion\":0,\"maxDays\":\"3\"}",
                "{\"expectedVersion\":0,\"maxDays\":3,\"stage\":\"NEW\"}"}) {
            assertThat(json.readTree(call("PUT", THRESHOLDS + "/SCREENING", malformed, hrToken).body()).path("code").asText())
                    .as(malformed).isEqualTo("INVALID_JSON");
        }
        for (String stage : new String[] {"HIRED", "REJECTED", "WITHDRAWN", "ONBOARDED", "new", "OTHER"}) {
            var response = call("PUT", THRESHOLDS + "/" + stage, "{\"expectedVersion\":0,\"maxDays\":3}", hrToken);
            assertThat(response.statusCode()).as(stage).isEqualTo(404);
        }
        assertThatThrownBy(() -> jdbc.update("UPDATE stage_sla_thresholds SET max_days = 0 WHERE stage = 'OFFER'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO stage_sla_thresholds(stage, max_days, updated_at) VALUES ('HIRED', 3, now())"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stage_sla_thresholds WHERE max_days IS NOT NULL", Long.class)).isZero();
    }
}
