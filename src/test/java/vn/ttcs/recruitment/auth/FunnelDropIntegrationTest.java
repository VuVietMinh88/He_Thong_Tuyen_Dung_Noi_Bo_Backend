package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Task 569 (story S8-07): the stage with the largest drop and the numbers that explain it. */
class FunnelDropIntegrationTest extends ReportingTestSupport {
    static final String FUNNEL = "/api/v1/reports/funnel?from=2026-09-25&to=2026-10-15";
    static final Instant DAY = Instant.parse("2026-10-01T03:00:00Z");

    @Test
    void theLargestDropIsTheStageThatLostTheMostApplicationsWithItsReasons() throws Exception {
        // NEW: first (still new) and one rejected straight away. SCREENING: three rejected, one withdrew, one advanced.
        rejectedAt(application(requisition, "n1@example.test", DAY, "NEW", null), "NEW", "Hồ sơ không đạt");
        for (String reason : new String[] {"Thiếu kinh nghiệm", "Thiếu kinh nghiệm", "Lương kỳ vọng cao"}) {
            UUID application = application(requisition, UUID.randomUUID() + "@example.test", DAY, "NEW", null);
            stageEvent(application, "NEW", "SCREENING", DAY.plusSeconds(60));
            rejectedAt(application, "SCREENING", reason);
        }
        UUID withdrew = application(requisition, "w@example.test", DAY, "NEW", null);
        path(withdrew, DAY, "SCREENING", "WITHDRAWN");
        UUID interviewed = application(requisition, "i@example.test", DAY, "NEW", null);
        path(interviewed, DAY, "SCREENING", "INTERVIEW");

        JsonNode funnel = okJson(FUNNEL + "&requisitionId=" + requisition, hrToken);
        assertThat(funnel.path("cohortSize").asLong()).isEqualTo(7);
        assertThat(funnel.path("totalDropped").asLong()).isEqualTo(5);
        JsonNode drop = funnel.path("largestDrop");
        assertThat(drop.path("stage").asText()).isEqualTo("SCREENING");
        assertThat(drop.path("label").asText()).isEqualTo("Sàng lọc");
        assertThat(drop.path("nextStage").asText()).isEqualTo("INTERVIEW");
        assertThat(drop.path("reached").asLong()).isEqualTo(5);
        assertThat(drop.path("advanced").asLong()).isEqualTo(1);
        assertThat(drop.path("dropped").asLong()).isEqualTo(4);
        assertThat(drop.path("rejected").asLong()).isEqualTo(3);
        assertThat(drop.path("withdrawn").asLong()).isEqualTo(1);
        assertThat(drop.path("dropRate").decimalValue()).isEqualByComparingTo("0.8000");
        assertThat(drop.path("conversionRate").decimalValue()).isEqualByComparingTo("0.2000");
        assertThat(drop.path("shareOfAllDrops").decimalValue()).isEqualByComparingTo("0.8000");
        assertThat(drop.path("rule").asText()).isEqualTo("MOST_DROPPED_THEN_HIGHEST_DROP_RATE_THEN_EARLIEST_STAGE");
        JsonNode reasons = drop.path("topRejectionReasons");
        assertThat(reasons).hasSize(2);
        assertThat(reasons.path(0).path("reason").asText()).isEqualTo("Thiếu kinh nghiệm");
        assertThat(reasons.path(0).path("count").asLong()).isEqualTo(2);
        assertThat(reasons.path(1).path("reason").asText()).isEqualTo("Lương kỳ vọng cao");
        // The explanation matches the chart: the same numbers as the stage row.
        JsonNode screening = funnel.path("stages").path(1);
        assertThat(screening.path("dropped").asLong()).isEqualTo(drop.path("dropped").asLong());
        assertThat(screening.path("reached").asLong()).isEqualTo(drop.path("reached").asLong());
    }

    @Test
    void reasonsCountEachApplicationOnceByItsLatestRejectionAndNameCandidateDeclines() throws Exception {
        // Declined a first offer, reopened by a renegotiated offer, declined again: two REJECTED events, one drop.
        UUID declined = application(requisition, "d@example.test", DAY, "NEW", null);
        path(declined, DAY, "SCREENING", "INTERVIEW", "OFFER");
        UUID first = offer(declined, "DECLINED", NOW.plusSeconds(86_400));
        decline(declined, first, "SALARY", DAY.plusSeconds(20_000));
        stageEvent(declined, "REJECTED", "OFFER", DAY.plusSeconds(30_000));
        UUID second = offer(declined, "DECLINED", NOW.plusSeconds(86_400));
        decline(declined, second, "OTHER_JOB", DAY.plusSeconds(40_000));
        jdbc.update("UPDATE job_applications SET stage = 'REJECTED' WHERE id = ?", declined);
        // Rejected by the company after the offer stage.
        UUID refused = application(requisition, "x@example.test", DAY, "NEW", null);
        path(refused, DAY, "SCREENING", "INTERVIEW", "OFFER");
        rejectedAt(refused, "OFFER", "Lương kỳ vọng cao");

        JsonNode funnel = okJson(FUNNEL + "&requisitionId=" + requisition, hrToken);
        JsonNode offerStage = funnel.path("stages").path(3);
        assertThat(offerStage.path("rejected").asLong()).isEqualTo(1);
        assertThat(offerStage.path("declined").asLong()).isEqualTo(1);
        assertThat(offerStage.path("dropped").asLong()).isEqualTo(2);
        JsonNode drop = funnel.path("largestDrop");
        assertThat(drop.path("stage").asText()).isEqualTo("OFFER");
        assertThat(drop.path("declined").asLong()).isEqualTo(1);
        JsonNode reasons = drop.path("topRejectionReasons");
        assertThat(reasons).hasSize(2);
        long counted = 0;
        for (JsonNode reason : reasons) {
            counted += reason.path("count").asLong();
        }
        assertThat(counted).isEqualTo(drop.path("dropped").asLong());
        assertThat(reasons.path(0).path("kind").asText()).isEqualTo("STAFF_REJECTION");
        assertThat(reasons.path(0).path("reason").asText()).isEqualTo("Lương kỳ vọng cao");
        assertThat(reasons.path(1).path("kind").asText()).isEqualTo("CANDIDATE_DECLINE");
        assertThat(reasons.path(1).path("reason").asText()).isEqualTo("Ứng viên từ chối offer: Nhận việc nơi khác");
    }

    // The candidate declines the offer through the response page: the offer's reason, and the candidate's stage event.
    private void decline(UUID application, UUID offer, String reason, java.time.Instant at) {
        jdbc.update("UPDATE offers SET decline_reason = ? WHERE id = ?", reason, offer);
        jdbc.update("""
                INSERT INTO application_stage_events(id,application_id,stage_before,stage_after,actor_type,occurred_at,offer_id)
                VALUES (?,?,'OFFER','REJECTED','CANDIDATE',?,?)
                """, UUID.randomUUID(), application, Timestamp.from(at), offer);
    }

    @Test
    void withoutAnyDropThereIsNoLargestDrop() throws Exception {
        UUID screened = application(requisition, "s@example.test", DAY, "NEW", null);
        path(screened, DAY, "SCREENING");
        JsonNode funnel = okJson(FUNNEL, hrToken);
        assertThat(funnel.path("totalDropped").asLong()).isZero();
        assertThat(funnel.path("largestDrop").isNull()).isTrue();
        assertThat(okJson(FUNNEL, outsiderToken).path("largestDrop").isNull()).isTrue();
    }

    // Rejected from the given stage with a reason of this name, as the stage API records it (reason id and name).
    private void rejectedAt(UUID application, String before, String reasonName) {
        UUID reason = catalog("REJECTION_REASON", reasonName, true);
        jdbc.update("""
                INSERT INTO application_stage_events(id,application_id,stage_before,stage_after,actor_type,actor_id,
                    occurred_at,rejection_reason_id,rejection_reason_name)
                VALUES (?,?,?,'REJECTED','STAFF',?,?,?,?)
                """, UUID.randomUUID(), application, before, hr, Timestamp.from(DAY.plusSeconds(120)), reason, reasonName);
        jdbc.update("UPDATE job_applications SET stage = 'REJECTED' WHERE id = ?", application);
    }
}
