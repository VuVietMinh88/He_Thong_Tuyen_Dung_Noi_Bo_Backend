package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import vn.ttcs.recruitment.sla.SlaMonitor;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 419: the daily job measures the time in the current stage from the stage history (or the receipt while NEW) in
 * calendar days of the business zone, flags late applications of active stages of recruiting requisitions only, and
 * clears flags that no longer apply.
 */
@TestPropertySource(properties = {"app.sla.cron=-", "app.notifications.poll-delay-ms=3600000"})
class SlaMonitorIntegrationTest extends ScreeningTestSupport {
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    @Autowired SlaMonitor monitor;

    @BeforeEach void thresholds() {
        jdbc.update("UPDATE stage_sla_thresholds SET max_days = NULL");
        jdbc.update("UPDATE stage_sla_thresholds SET max_days = 3 WHERE stage = 'NEW'");
        jdbc.update("UPDATE stage_sla_thresholds SET max_days = 5 WHERE stage = 'SCREENING'");
        jdbc.execute("TRUNCATE application_sla_flags");
    }

    @Test
    void newApplicationsAreMeasuredFromReceiptAndOthersFromTheirLatestEntryIntoTheStage() {
        Instant received = jdbc.queryForObject("SELECT received_at FROM job_applications WHERE id=?", Timestamp.class,
                first.applicationId()).toInstant();
        // NEW allows 3 calendar days: late from the start of the fourth day after the day of receipt.
        Instant due = startOf(LocalDate.ofInstant(received, ZONE).plusDays(4));
        clock.set(due.minusSeconds(1));
        assertThat(monitor.run().flagged()).isZero();
        clock.set(due);
        monitor.run();
        assertThat(flagged()).containsExactlyInAnyOrder(first.applicationId(), other.applicationId());
        assertThat(jdbc.queryForObject("SELECT due_at FROM application_sla_flags WHERE application_id=?", Timestamp.class,
                first.applicationId()).toInstant()).isEqualTo(due);

        // first moves to SCREENING now: its NEW flag is stale and cleared, and SCREENING counts from this move.
        Instant moved = due;
        jdbc.update("UPDATE job_applications SET stage='SCREENING' WHERE id=?", first.applicationId());
        jdbc.update("""
                INSERT INTO application_stage_events(id,application_id,stage_before,stage_after,actor_type,actor_id,occurred_at)
                VALUES (?,?,'NEW','SCREENING','STAFF',?,?)
                """, UUID.randomUUID(), first.applicationId(), hr, Timestamp.from(moved));
        SlaMonitor.Result result = monitor.run();
        assertThat(result.cleared()).isEqualTo(1);
        assertThat(flagged()).containsExactly(other.applicationId());
        clock.set(startOf(LocalDate.ofInstant(moved, ZONE).plusDays(6)));
        monitor.run();
        assertThat(flagged()).containsExactlyInAnyOrder(first.applicationId(), other.applicationId());
        assertThat(jdbc.queryForObject("SELECT stage FROM application_sla_flags WHERE application_id=?", String.class,
                first.applicationId())).isEqualTo("SCREENING");
    }

    @Test
    void anApplicationThatEnteredItsStageAtSixInTheMorningIsNotLateOnItsLastAllowedDay() {
        // first entered SCREENING (5 days) at 06:00 on 15 October: 20 October is its last allowed day.
        jdbc.update("UPDATE job_applications SET stage='SCREENING' WHERE id=?", first.applicationId());
        jdbc.update("""
                INSERT INTO application_stage_events(id,application_id,stage_before,stage_after,actor_type,actor_id,occurred_at)
                VALUES (?,?,'NEW','SCREENING','STAFF',?,?)
                """, UUID.randomUUID(), first.applicationId(), hr,
                Timestamp.from(LocalDateTime.of(2026, 10, 15, 6, 0).atZone(ZONE).toInstant()));
        // The daily run at 07:00 on 20 October comes more than five times 24 hours after the entry: not late yet.
        clock.set(LocalDateTime.of(2026, 10, 20, 7, 0).atZone(ZONE).toInstant());
        monitor.run();
        assertThat(flagged()).doesNotContain(first.applicationId());
        clock.set(LocalDateTime.of(2026, 10, 20, 23, 59, 59).atZone(ZONE).toInstant());
        monitor.run();
        assertThat(flagged()).doesNotContain(first.applicationId());
        Instant due = LocalDateTime.of(2026, 10, 21, 0, 0).atZone(ZONE).toInstant();
        clock.set(due);
        monitor.run();
        assertThat(flagged()).contains(first.applicationId());
        assertThat(jdbc.queryForObject("SELECT due_at FROM application_sla_flags WHERE application_id=?", Timestamp.class,
                first.applicationId()).toInstant()).isEqualTo(due);
    }

    @Test
    void applicationsOfARequisitionThatStoppedRecruitingAreNotFlaggedUntilItRecruitsAgain() throws Exception {
        clock.set(ApplicationTestSupport.NOW.plus(Duration.ofDays(4)));
        monitor.run();
        assertThat(flagged()).containsExactlyInAnyOrder(first.applicationId(), other.applicationId());
        jdbc.update("UPDATE recruitment_requisitions SET status='PAUSED' WHERE id=?", requisition);
        jdbc.update("UPDATE recruitment_requisitions SET status='FILLED' WHERE id=?", otherRequisition);
        // The list hides them at once; the next run clears their flags.
        assertThat(ok(call("GET", "/api/v1/sla/overdue", null, token(hr))).path("totalItems").asLong()).isZero();
        assertThat(monitor.run().cleared()).isEqualTo(2);
        assertThat(flagged()).isEmpty();
        // Resuming the paused requisition monitors its applications again; a cancelled one is never flagged.
        jdbc.update("UPDATE recruitment_requisitions SET status='APPROVED' WHERE id=?", requisition);
        jdbc.update("UPDATE recruitment_requisitions SET status='CANCELLED' WHERE id=?", otherRequisition);
        monitor.run();
        assertThat(flagged()).containsExactly(first.applicationId());
    }

    @Test
    void finalStagesAndRemovedThresholdsAreNeverFlagged() {
        clock.set(ApplicationTestSupport.NOW.plus(Duration.ofDays(30)));
        jdbc.update("UPDATE job_applications SET stage='WITHDRAWN' WHERE id=?", other.applicationId());
        monitor.run();
        assertThat(flagged()).containsExactly(first.applicationId());
        jdbc.update("UPDATE job_applications SET stage='REJECTED' WHERE id=?", first.applicationId());
        monitor.run();
        assertThat(flagged()).isEmpty();
        jdbc.update("UPDATE job_applications SET stage='NEW' WHERE id IN (?,?)", first.applicationId(), other.applicationId());
        monitor.run();
        assertThat(flagged()).hasSize(2);
        jdbc.update("UPDATE stage_sla_thresholds SET max_days = NULL WHERE stage = 'NEW'");
        assertThat(monitor.run().cleared()).isEqualTo(2);
        assertThat(flagged()).isEmpty();
    }

    @Test
    void concurrentRunsLeaveOneFlagPerApplication() throws Exception {
        clock.set(ApplicationTestSupport.NOW.plus(Duration.ofDays(30)));
        List<Callable<SlaMonitor.Result>> runs = List.of(monitor::run, monitor::run, monitor::run);
        try (var executor = Executors.newFixedThreadPool(3)) {
            for (var result : executor.invokeAll(runs)) result.get();
        }
        assertThat(flagged()).containsExactlyInAnyOrder(first.applicationId(), other.applicationId());
    }

    @Test
    void theOverdueListShowsOnlyFlaggedApplicationsTheCallerMayReadAndLogsTheReads() throws Exception {
        jdbc.execute("TRUNCATE candidate_access_events");
        clock.set(ApplicationTestSupport.NOW.plus(Duration.ofDays(4)));
        monitor.run();
        // Tokens issued four days ago have expired: sign in again at the moved clock.
        String recruiterToken = token(recruiter), hrToken = token(hr);
        var mine = ok(call("GET", "/api/v1/sla/overdue", null, recruiterToken));
        assertThat(mine.path("totalItems").asLong()).isEqualTo(1);
        var item = mine.path("items").path(0);
        assertThat(item.path("applicationId").asText()).isEqualTo(first.applicationId().toString());
        assertThat(item.path("stage").asText()).isEqualTo("NEW");
        assertThat(item.path("maxDays").asInt()).isEqualTo(3);
        assertThat(item.path("daysInStage").asLong()).isEqualTo(4);
        assertThat(ok(call("GET", "/api/v1/sla/overdue", null, hrToken)).path("totalItems").asLong()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_access_events WHERE actor_id=? AND outcome='GRANTED'",
                Long.class, recruiter)).isEqualTo(1);
        // A flag of a stage the application has left is not shown even before the next run clears it.
        jdbc.update("UPDATE job_applications SET stage='SCREENING' WHERE id=?", first.applicationId());
        assertThat(ok(call("GET", "/api/v1/sla/overdue", null, recruiterToken)).path("totalItems").asLong()).isZero();
        assertThat(call("GET", "/api/v1/sla/overdue?size=101", null, hrToken).statusCode()).isEqualTo(400);
    }

    private List<UUID> flagged() {
        return jdbc.queryForList("SELECT application_id FROM application_sla_flags", UUID.class);
    }

    private static Instant startOf(LocalDate day) {
        return day.atStartOfDay(ZONE).toInstant();
    }
}
