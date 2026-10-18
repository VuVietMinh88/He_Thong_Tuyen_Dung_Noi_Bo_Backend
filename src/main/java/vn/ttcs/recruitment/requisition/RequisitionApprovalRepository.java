package vn.ttcs.recruitment.requisition;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * The approval history of requisitions (V19 requisition_workflow_events). This class only adds and reads rows: no
 * event is ever changed or removed. Every writer holds the requisition row lock, which every writer of a requisition
 * takes first, so the next revision cannot be taken by another writer in between; one that skipped the lock would
 * fail on requisition_workflow_events_revision_key.
 */
@Repository
public class RequisitionApprovalRepository {
    private static final String SELECT_EVENTS = """
            SELECT e.id, e.revision, e.event_type, e.status_before, e.status_after, e.round_number, e.level_number,
                   e.flow_id, e.flow_version, e.level_count, e.salary_basis, e.actor_id, a.full_name AS actor_name,
                   e.occurred_at, e.comment
            FROM requisition_workflow_events e
            JOIN user_accounts a ON a.id = e.actor_id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public RequisitionApprovalRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // Task 257: the round a new submission starts.
    public int nextRound(UUID requisitionId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(MAX(round_number), 0) + 1 FROM requisition_workflow_events
                WHERE requisition_id = :requisitionId
                """, new MapSqlParameterSource("requisitionId", requisitionId), Integer.class);
    }

    // Task 257: a round starts. The row is the snapshot of the round: the flow version, how many of its levels apply,
    // and the salary that decided it.
    public void appendSubmitted(UUID requisitionId, int round, RequisitionStatus before, SelectedChain chain,
                                long salaryBasis, UUID actorId, Instant at, String comment) {
        append(requisitionId, new MapSqlParameterSource()
                .addValue("eventType", ApprovalEventType.SUBMITTED.name())
                .addValue("statusBefore", before.name())
                .addValue("statusAfter", RequisitionStatus.PENDING_APPROVAL.name())
                .addValue("roundNumber", round)
                .addValue("levelNumber", null)
                .addValue("flowId", chain.flowId())
                .addValue("flowVersion", chain.version())
                .addValue("levelCount", chain.levelCount())
                .addValue("salaryBasis", salaryBasis)
                .addValue("actorId", actorId)
                .addValue("occurredAt", at)
                .addValue("comment", comment));
    }

    // Every event of one requisition, oldest first by revision (not by time: two events may share a timestamp).
    public List<ApprovalEvent> findEvents(UUID requisitionId) {
        return jdbc.query(SELECT_EVENTS + " WHERE e.requisition_id = :requisitionId ORDER BY e.revision",
                new MapSqlParameterSource("requisitionId", requisitionId), RequisitionApprovalRepository::event);
    }

    private void append(UUID requisitionId, MapSqlParameterSource row) {
        row.addValue("requisitionId", requisitionId);
        int revision = jdbc.queryForObject("""
                SELECT COALESCE(MAX(revision), 0) + 1 FROM requisition_workflow_events
                WHERE requisition_id = :requisitionId
                """, row, Integer.class);
        Instant at = (Instant) row.getValue("occurredAt");
        jdbc.update("""
                INSERT INTO requisition_workflow_events (id, requisition_id, revision, event_type, status_before,
                    status_after, round_number, level_number, flow_id, flow_version, level_count, salary_basis,
                    actor_id, occurred_at, comment)
                VALUES (:id, :requisitionId, :revision, :eventType, :statusBefore, :statusAfter, :roundNumber,
                    :levelNumber, :flowId, :flowVersion, :levelCount, :salaryBasis, :actorId, :occurredAt, :comment)
                """, row.addValue("id", UUID.randomUUID())
                .addValue("revision", revision)
                // OffsetDateTime in UTC is sent as TIMESTAMPTZ, independent of the session or JVM time zone.
                .addValue("occurredAt", at.atOffset(ZoneOffset.UTC)));
    }

    private static ApprovalEvent event(ResultSet row, int number) throws SQLException {
        return new ApprovalEvent(row.getObject("id", UUID.class), row.getInt("revision"),
                ApprovalEventType.valueOf(row.getString("event_type")),
                RequisitionStatus.valueOf(row.getString("status_before")),
                RequisitionStatus.valueOf(row.getString("status_after")),
                row.getObject("round_number", Integer.class), row.getObject("level_number", Integer.class),
                row.getObject("flow_id", UUID.class), row.getObject("flow_version", Integer.class),
                row.getObject("level_count", Integer.class), row.getObject("salary_basis", Long.class),
                row.getObject("actor_id", UUID.class), row.getString("actor_name"),
                row.getObject("occurred_at", OffsetDateTime.class).toInstant(), row.getString("comment"));
    }
}
