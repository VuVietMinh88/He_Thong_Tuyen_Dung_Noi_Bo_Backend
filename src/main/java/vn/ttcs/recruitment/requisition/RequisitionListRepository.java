package vn.ttcs.recruitment.requisition;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

// Task 297 (story S3-08): the search behind GET /requisitions. JDBC because every filter is optional: only constant SQL
// fragments are appended and every value is bound, so a filter value never becomes SQL. It returns the ids of one page
// and the total; RequisitionService loads those requisitions in the same transaction. The statuses are bound by name:
// PgJDBC cannot bind a Java enum. Task 285: the scope of a SCOPED caller adds the requisitions assigned to them, with
// the constant subquery of RecruiterAssignmentAccess.
@Repository
class RequisitionListRepository {
    private final NamedParameterJdbcTemplate jdbc;

    RequisitionListRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // The ids of one page, newest first, and how many requisitions match in all.
    record IdPage(List<UUID> ids, long total) { }

    // Task 285: what a SCOPED caller may list: the departments they manage (with their sub-departments; may be empty)
    // or the requisitions they are assigned to now (RecruiterAssignmentAccess).
    record Scope(Set<UUID> departmentIds, UUID assigneeId) {
        Scope {
            departmentIds = Set.copyOf(departmentIds);
            Objects.requireNonNull(assigneeId);
        }
    }

    // scope: null = every requisition (READ_ALL); otherwise the managed departments or the assigned requisitions.
    // departmentId is that department only, without its sub-departments; recruiterId is the current PRIMARY recruiter
    // (V16); the needed-by bounds are inclusive and leave out requisitions without a needed-by date. Every filter is
    // ANDed with the scope, so it only narrows it. Newest first; the id keeps the order stable when two requisitions
    // share the same creation time. The count and the page share one WHERE and one set of parameters.
    IdPage search(RequisitionListFilter filter, Scope scope, int page, int size) {
        var parameters = new MapSqlParameterSource();
        var where = new StringBuilder(" FROM recruitment_requisitions r WHERE 1 = 1 ");
        if (scope != null) {
            parameters.addValue(RecruiterAssignmentAccess.ASSIGNEE, scope.assigneeId());
            String assigned = "r.id IN (" + RecruiterAssignmentAccess.ASSIGNED_REQUISITION_IDS + ")";
            if (scope.departmentIds().isEmpty()) {
                // IN () would be a syntax error: a caller who manages nothing lists what they are assigned to.
                where.append(" AND ").append(assigned).append(' ');
            } else {
                parameters.addValue("scope", scope.departmentIds());
                // Parenthesised, so every filter below narrows both branches.
                where.append(" AND (r.department_id IN (:scope) OR ").append(assigned).append(") ");
            }
        }
        if (!filter.statuses().isEmpty()) {
            parameters.addValue("statuses", filter.statuses().stream().map(Enum::name).toList());
            where.append(" AND r.status IN (:statuses) ");
        }
        if (filter.departmentId() != null) {
            parameters.addValue("departmentId", filter.departmentId());
            where.append(" AND r.department_id = :departmentId ");
        }
        if (filter.recruiterId() != null) {
            parameters.addValue("recruiterId", filter.recruiterId());
            where.append(" AND EXISTS (SELECT 1 FROM requisition_recruiters rr WHERE rr.requisition_id = r.id"
                    + " AND rr.recruiter_id = :recruiterId AND rr.assignment_role = 'PRIMARY') ");
        }
        if (filter.neededFrom() != null) {
            parameters.addValue("neededFrom", filter.neededFrom());
            where.append(" AND r.needed_by >= :neededFrom ");
        }
        if (filter.neededTo() != null) {
            parameters.addValue("neededTo", filter.neededTo());
            where.append(" AND r.needed_by <= :neededTo ");
        }
        long total = jdbc.queryForObject("SELECT count(*)" + where, parameters, Long.class);
        parameters.addValue("size", size).addValue("offset", (long) page * size);
        List<UUID> ids = jdbc.query("SELECT r.id" + where
                        + " ORDER BY r.created_at DESC, r.id DESC LIMIT :size OFFSET :offset",
                parameters, (row, number) -> row.getObject("id", UUID.class));
        return new IdPage(ids, total);
    }
}
