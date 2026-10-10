package vn.ttcs.recruitment.requisition;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

// Task 286: the history of recruiter changes (V17). Rows are only added, never changed or removed.
@Repository
public class RequisitionRecruiterChangeRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public RequisitionRecruiterChangeRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records one change with the next revision of the requisition. The caller holds the requisition row lock, which
     * every writer of the history takes first, so no other change can take the same revision in between; one that
     * skipped the lock would fail on requisition_recruiter_changes_revision_key.
     */
    public void record(UUID requisitionId, RecruiterChange change, UUID changedBy, Instant changedAt, String note) {
        var parameters = new MapSqlParameterSource("requisitionId", requisitionId);
        int revision = jdbc.queryForObject("""
                SELECT COALESCE(MAX(revision), 0) + 1 FROM requisition_recruiter_changes
                WHERE requisition_id = :requisitionId
                """, parameters, Integer.class);
        jdbc.update("""
                INSERT INTO requisition_recruiter_changes (id, requisition_id, revision, change_type, recruiter_id,
                    previous_recruiter_id, changed_by, changed_at, note)
                VALUES (:id, :requisitionId, :revision, :changeType, :recruiterId, :previousRecruiterId, :changedBy,
                    :changedAt, :note)
                """, parameters.addValue("id", UUID.randomUUID())
                .addValue("revision", revision)
                .addValue("changeType", change.type().name())
                .addValue("recruiterId", change.recruiterId())
                .addValue("previousRecruiterId", change.previousRecruiterId())
                .addValue("changedBy", changedBy)
                // OffsetDateTime in UTC is sent as TIMESTAMPTZ, independent of the session or JVM time zone.
                .addValue("changedAt", changedAt.atOffset(ZoneOffset.UTC))
                .addValue("note", note));
    }

    // The history of one requisition, newest first by revision (not by time: two changes may share a timestamp).
    public List<RecruiterChangeView> findByRequisition(UUID requisitionId) {
        return jdbc.query("""
                SELECT c.id, c.revision, c.change_type, c.recruiter_id, a.full_name AS assigned_to,
                       c.previous_recruiter_id, p.full_name AS previous_assigned_to, c.changed_by,
                       b.full_name AS assigned_by, c.changed_at, c.note
                FROM requisition_recruiter_changes c
                JOIN user_accounts a ON a.id = c.recruiter_id
                LEFT JOIN user_accounts p ON p.id = c.previous_recruiter_id
                JOIN user_accounts b ON b.id = c.changed_by
                WHERE c.requisition_id = :requisitionId
                ORDER BY c.revision DESC
                """, new MapSqlParameterSource("requisitionId", requisitionId),
                (row, number) -> new RecruiterChangeView(row.getObject("id", UUID.class), row.getInt("revision"),
                        RecruiterChangeType.valueOf(row.getString("change_type")),
                        row.getObject("recruiter_id", UUID.class), row.getString("assigned_to"),
                        row.getObject("previous_recruiter_id", UUID.class), row.getString("previous_assigned_to"),
                        row.getObject("changed_by", UUID.class), row.getString("assigned_by"),
                        row.getObject("changed_at", OffsetDateTime.class).toInstant(), row.getString("note")));
    }
}
