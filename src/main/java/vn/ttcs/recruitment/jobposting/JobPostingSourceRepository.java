package vn.ttcs.recruitment.jobposting;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.ttcs.recruitment.requisition.RecruiterAssignmentAccess;
import vn.ttcs.recruitment.requisition.RequisitionStatus;
import vn.ttcs.recruitment.security.AccessScope;

import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

// Task 303: what job postings read from requisitions and their recruiters. JDBC with only the columns a posting needs,
// never the RecruitmentRequisition entity: posting code cannot call a requisition mutator or have Hibernate flush a
// requisition, so editing a posting never changes its requisition (task 304).
@Repository
class JobPostingSourceRepository {
    private final NamedParameterJdbcTemplate jdbc;

    JobPostingSourceRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // The requisition a posting is made from, with the texts it inherits.
    record SourceRequisition(UUID id, RequisitionStatus status, String positionName, String jobDescription,
                             String candidateRequirements) { }

    // Locks the requisition row until commit, in the lock order of every requisition write (account, session,
    // requisition): a save of the requisition, a decision of its approval or a change of its recruiters waits for
    // this transaction, and one committed while this request waited is seen. FOR NO KEY UPDATE like the requisition
    // writes, so it does not block foreign key checks (the posting's insert). Only the requisition row is locked, not
    // its position. Empty when the requisition does not exist. Read-write transactions only.
    // The query reads the requisition table alone and the position name with a subquery, never a join: after a wait,
    // PostgreSQL reads the locked row again but not the other rows of a join, so a save that moved the requisition to
    // another position while this request waited would make the joined row vanish (400 "does not exist" instead of
    // the 409 the requisition's status gives). The subquery runs again on the row as it is after the wait.
    Optional<SourceRequisition> lockSource(UUID id) {
        return jdbc.query("""
                SELECT r.id, r.status,
                       (SELECT pos.name FROM positions pos WHERE pos.id = r.position_id) AS position_name,
                       r.job_description, r.candidate_requirements
                FROM recruitment_requisitions r
                WHERE r.id = :id
                FOR NO KEY UPDATE
                """, new MapSqlParameterSource("id", id),
                (row, number) -> new SourceRequisition(row.getObject("id", UUID.class),
                        RequisitionStatus.valueOf(row.getString("status")), row.getString("position_name"),
                        row.getString("job_description"), row.getString("candidate_requirements")))
                .stream().findFirst();
    }

    // The requisitions a posting may be made from: status in statuses, no posting yet, and for SCOPED callers only
    // those they are assigned to (RecruiterAssignmentAccess; the filter is part of the SQL query). Newest first; the id
    // keeps the order stable. The statuses are bound by name: PgJDBC cannot bind a Java enum.
    SourceRequisitionPage findSources(AccessScope scope, UUID me, Set<RequisitionStatus> statuses, int page,
                                      int size) {
        var parameters = new MapSqlParameterSource("sourceStatuses",
                statuses.stream().map(Enum::name).toList());
        String where = """
                 WHERE r.status IN (:sourceStatuses)
                   AND NOT EXISTS (SELECT 1 FROM job_postings jp WHERE jp.requisition_id = r.id)
                """;
        if (scope != AccessScope.ALL) {
            parameters.addValue(RecruiterAssignmentAccess.ASSIGNEE, me);
            where += " AND r.id IN (" + RecruiterAssignmentAccess.ASSIGNED_REQUISITION_IDS + ") ";
        }
        long total = jdbc.queryForObject("SELECT count(*) FROM recruitment_requisitions r" + where, parameters,
                Long.class);
        parameters.addValue("size", size).addValue("offset", (long) page * size);
        var items = jdbc.query("""
                SELECT r.id, r.position_id, pos.name AS position_name, r.department_id, d.name AS department_name,
                       r.headcount, r.needed_by
                FROM recruitment_requisitions r
                JOIN positions pos ON pos.id = r.position_id
                JOIN departments d ON d.id = r.department_id
                """ + where + " ORDER BY r.created_at DESC, r.id DESC LIMIT :size OFFSET :offset", parameters,
                (row, number) -> new SourceRequisitionView(row.getObject("id", UUID.class),
                        row.getObject("position_id", UUID.class), row.getString("position_name"),
                        row.getObject("department_id", UUID.class), row.getString("department_name"),
                        row.getInt("headcount"), row.getObject("needed_by", LocalDate.class)));
        return new SourceRequisitionPage(items, page, size, total, (total + size - 1) / size);
    }
}
