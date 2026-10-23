package vn.ttcs.recruitment.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Task 345 (story S4-06): the stage history of an application (V34, application_stage_events). Both writes join the
 * caller's transaction (MANDATORY), which holds the application's requisition and row locks
 * (ApplicationWithdrawalAccess.lock): the stage and its event commit together or not at all. Never REQUIRES_NEW: the
 * event's foreign key takes only FOR KEY SHARE on the locked application row, so an event written in its own
 * transaction would not wait for the caller and would commit even when the stage change does not.
 */
@Repository
class ApplicationStageEventRepository {
    private final JdbcTemplate jdbc;

    ApplicationStageEventRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    // The stage change itself, only from the stage read under the row lock: 0 rows means the row is not what the caller
    // locked, which the caller treats as a failure. No key column changes, so no parent row is locked.
    @Transactional(propagation = Propagation.MANDATORY)
    boolean moveToWithdrawn(UUID applicationId, ApplicationStage before) {
        return jdbc.update("UPDATE job_applications SET stage = 'WITHDRAWN' WHERE id = ? AND stage = ?",
                applicationId, before.name()) == 1;
    }

    // The candidate's own withdrawal: actor CANDIDATE without an account (V34 CHECKs), at the caller's clock time.
    @Transactional(propagation = Propagation.MANDATORY)
    void recordCandidateWithdrawal(UUID applicationId, ApplicationStage before, String reason, Instant at) {
        jdbc.update("""
                INSERT INTO application_stage_events(id,application_id,stage_before,stage_after,actor_type,actor_id,
                    reason,occurred_at)
                VALUES (?,?,?,'WITHDRAWN','CANDIDATE',NULL,?,?)
                """, UUID.randomUUID(), applicationId, before.name(), reason, Timestamp.from(at));
    }

    // When the application was withdrawn: its one WITHDRAWN event (unique index), or empty for a row moved to WITHDRAWN
    // without one.
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<Instant> withdrawnAt(UUID applicationId) {
        return jdbc.query("""
                SELECT occurred_at FROM application_stage_events WHERE application_id = ? AND stage_after = 'WITHDRAWN'
                """, (row, number) -> row.getTimestamp(1).toInstant(), applicationId).stream().findFirst();
    }
}
