package vn.ttcs.recruitment.pipeline;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.ttcs.recruitment.application.ApplicationStage;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Task 380 (story S5-03): the SQL of a staff stage change. Every method joins the caller's transaction (MANDATORY),
 * ApplicationStageChangeService's READ COMMITTED one, so the locks last until it ends and the stage and its history row
 * (V34 application_stage_events, the table of the candidate's withdrawal) commit together or not at all.
 * <p>
 * Lock order, the house order of ApplicationWithdrawalAccess: the requisition FOR NO KEY UPDATE, then the application
 * FOR NO KEY UPDATE. Under READ COMMITTED a lock that waited reads the newest committed row, so a withdrawal or another
 * stage change that committed meanwhile is seen.
 */
@Repository
class ApplicationStageChangeRepository {
    private final JdbcTemplate jdbc;

    ApplicationStageChangeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // The application's requisition, read without a lock: it never changes. Empty for an unknown application.
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<UUID> requisitionOf(UUID applicationId) {
        return jdbc.query("SELECT requisition_id FROM job_applications WHERE id = ?",
                (row, number) -> row.getObject(1, UUID.class), applicationId).stream().findFirst();
    }

    // The application's key is RESTRICT (V26) and no API deletes a requisition, so the row is always there.
    @Transactional(propagation = Propagation.MANDATORY)
    void lockRequisition(UUID requisitionId) {
        if (jdbc.queryForList("SELECT 1 FROM recruitment_requisitions WHERE id = ? FOR NO KEY UPDATE", Integer.class,
                requisitionId).size() != 1) {
            throw new IllegalStateException("The application's requisition was not locked");
        }
    }

    // The stage of the application as locked; empty when it was deleted meanwhile (manual cleanup).
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<ApplicationStage> lockApplication(UUID applicationId) {
        return jdbc.query("SELECT stage FROM job_applications WHERE id = ? FOR NO KEY UPDATE",
                (row, number) -> ApplicationStage.valueOf(row.getString(1)), applicationId).stream().findFirst();
    }

    // Only from the stage read under the lock: 0 rows means the row is not what the caller locked. No key column
    // changes, so no parent row is locked.
    @Transactional(propagation = Propagation.MANDATORY)
    void move(UUID applicationId, ApplicationStage before, ApplicationStage after) {
        if (jdbc.update("UPDATE job_applications SET stage = ? WHERE id = ? AND stage = ?", after.name(), applicationId,
                before.name()) != 1) {
            throw new IllegalStateException("The locked application changed its stage");
        }
    }

    // Task 381: a value of the REJECTION_REASON catalog (V10), FOR SHARE until the change commits, so HR cannot rename,
    // deactivate or delete it between this check and the history row (they lock it FOR UPDATE and wait). Empty for an
    // unknown id or a value of another catalog. Locked after the application: catalog writes never lock applications
    // (a deletion's RESTRICT checks take FOR KEY SHARE, which a FOR NO KEY UPDATE lock does not block), so no cycle.
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<RejectionReason> lockRejectionReason(UUID reasonId) {
        return jdbc.query("""
                SELECT id, name, active FROM recruitment_catalog_items
                WHERE id = ? AND catalog_type = 'REJECTION_REASON' FOR SHARE
                """, (row, number) -> new RejectionReason(row.getObject(1, UUID.class), row.getString(2),
                        row.getBoolean(3)), reasonId).stream().findFirst();
    }

    // One STAFF row of the history: the actor is the caller's account (V34 requires it for STAFF); a rejection's reason
    // as an id and the name it has now (V40); whether the interview was skipped (the HR exception, V40).
    @Transactional(propagation = Propagation.MANDATORY)
    void record(UUID applicationId, ApplicationStage before, ApplicationStage after, UUID actorId, Instant at,
                RejectionReason reasonOrNull, boolean interviewSkipped) {
        jdbc.update("""
                INSERT INTO application_stage_events(id,application_id,stage_before,stage_after,actor_type,actor_id,
                    reason,occurred_at,rejection_reason_id,rejection_reason_name,interview_skipped)
                VALUES (?,?,?,?,'STAFF',?,NULL,?,?,?,?)
                """, UUID.randomUUID(), applicationId, before.name(), after.name(), actorId, Timestamp.from(at),
                reasonOrNull == null ? null : reasonOrNull.id(), reasonOrNull == null ? null : reasonOrNull.name(),
                interviewSkipped);
    }

    // A rejection reason as locked: its catalog name and whether HR still offers it.
    record RejectionReason(UUID id, String name, boolean active) { }
}
