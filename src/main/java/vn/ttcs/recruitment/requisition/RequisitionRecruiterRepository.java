package vn.ttcs.recruitment.requisition;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

// Task 283: the recruiters of each requisition (V16). JDBC, not JPA: a handover deletes the old primary row before it
// inserts the new one, and Hibernate would flush the insert first, which the primary key and the one-primary index
// would refuse.
@Repository
public class RequisitionRecruiterRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public RequisitionRecruiterRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // A plain read, primary first. A caller that changes the recruiters must already hold the requisition row lock,
    // which every writer of this table takes first, so what it reads cannot change before it writes.
    public RequisitionAssignment findByRequisition(UUID requisitionId) {
        var rows = jdbc.query("""
                SELECT recruiter_id, assignment_role, assigned_by, assigned_at
                FROM requisition_recruiters
                WHERE requisition_id = :requisitionId
                ORDER BY assignment_role, assigned_at, recruiter_id
                """, new MapSqlParameterSource("requisitionId", requisitionId),
                (row, number) -> new RequisitionRecruiter(row.getObject("recruiter_id", UUID.class),
                        RequisitionRecruiterRole.valueOf(row.getString("assignment_role")),
                        row.getObject("assigned_by", UUID.class),
                        row.getObject("assigned_at", OffsetDateTime.class).toInstant()));
        return RequisitionAssignment.of(requisitionId, rows);
    }

    // Task 284: the recruiters with the names to show, primary first, supporting in the order they were added.
    // Names are read now, not stored: a renamed account shows its new name.
    public record AssignedRecruiterRow(UUID recruiterId, RequisitionRecruiterRole role, String fullName,
                                       UUID assignedById, String assignedByName, Instant assignedAt) { }

    public List<AssignedRecruiterRow> findAssigned(UUID requisitionId) {
        return jdbc.query("""
                SELECT r.recruiter_id, r.assignment_role, a.full_name, r.assigned_by, b.full_name AS assigned_by_name,
                       r.assigned_at
                FROM requisition_recruiters r
                JOIN user_accounts a ON a.id = r.recruiter_id
                JOIN user_accounts b ON b.id = r.assigned_by
                WHERE r.requisition_id = :requisitionId
                ORDER BY r.assignment_role, r.assigned_at, r.recruiter_id
                """, new MapSqlParameterSource("requisitionId", requisitionId),
                (row, number) -> new AssignedRecruiterRow(row.getObject("recruiter_id", UUID.class),
                        RequisitionRecruiterRole.valueOf(row.getString("assignment_role")), row.getString("full_name"),
                        row.getObject("assigned_by", UUID.class), row.getString("assigned_by_name"),
                        row.getObject("assigned_at", OffsetDateTime.class).toInstant()));
    }

    /**
     * Writes one change returned by RequisitionAssignment. The caller holds the requisition row lock. A handover first
     * deletes the old primary (and the new primary's supporting row when they are promoted), then inserts the new
     * primary. The number of deleted rows is checked: any other number means the rows changed without the lock, and
     * the exception rolls the whole transaction back.
     */
    public void apply(UUID requisitionId, RecruiterChange change, UUID assignedBy, Instant assignedAt) {
        var parameters = new MapSqlParameterSource("requisitionId", requisitionId)
                .addValue("recruiterId", change.recruiterId())
                .addValue("assignedBy", assignedBy)
                // OffsetDateTime in UTC is sent as TIMESTAMPTZ, independent of the session or JVM time zone.
                .addValue("assignedAt", assignedAt.atOffset(ZoneOffset.UTC));
        switch (change.type()) {
            case PRIMARY_ASSIGNED, PRIMARY_HANDED_OVER -> {
                int deleted = jdbc.update("""
                        DELETE FROM requisition_recruiters
                        WHERE requisition_id = :requisitionId
                          AND (assignment_role = 'PRIMARY' OR recruiter_id = :recruiterId)
                        """, parameters);
                boolean expected = change.type() == RecruiterChangeType.PRIMARY_ASSIGNED
                        ? deleted == 0 : deleted == 1 || deleted == 2;
                requireExpected(expected, change, deleted);
                insert(parameters, RequisitionRecruiterRole.PRIMARY);
            }
            case SUPPORTING_ADDED -> insert(parameters, RequisitionRecruiterRole.SUPPORTING);
            case SUPPORTING_REMOVED -> {
                int deleted = jdbc.update("""
                        DELETE FROM requisition_recruiters
                        WHERE requisition_id = :requisitionId AND recruiter_id = :recruiterId
                          AND assignment_role = 'SUPPORTING'
                        """, parameters);
                requireExpected(deleted == 1, change, deleted);
            }
        }
    }

    private void insert(MapSqlParameterSource parameters, RequisitionRecruiterRole role) {
        jdbc.update("""
                INSERT INTO requisition_recruiters (requisition_id, recruiter_id, assignment_role, assigned_by,
                    assigned_at)
                VALUES (:requisitionId, :recruiterId, :role, :assignedBy, :assignedAt)
                """, new MapSqlParameterSource(parameters.getValues()).addValue("role", role.name()));
    }

    private static void requireExpected(boolean expected, RecruiterChange change, int deleted) {
        if (!expected) {
            throw new IllegalStateException(change.type() + " deleted " + deleted
                    + " recruiter rows: the recruiters changed without the requisition lock");
        }
    }
}
