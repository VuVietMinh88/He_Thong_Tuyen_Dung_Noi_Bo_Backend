package vn.ttcs.recruitment.reporting;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.util.UUID;

/**
 * The structural filters of a report: one requisition, one department (that department only, like the requisition
 * list, not its sub-departments) and one position (job title). Each filter only narrows the viewer's scope; it never
 * widens it. sql() names only the filters that are set, so an unfiltered report keeps the simplest plan.
 */
public record ReportFilters(UUID requisitionId, UUID departmentId, UUID positionId) {
    static final ReportFilters NONE = new ReportFilters(null, null, null);

    /** AND conditions over the alias r (recruitment_requisitions); empty when no filter is set. */
    String sql() {
        StringBuilder sql = new StringBuilder();
        if (requisitionId != null) {
            sql.append(" AND r.id = :filterRequisition");
        }
        if (departmentId != null) {
            sql.append(" AND r.department_id = :filterDepartment");
        }
        if (positionId != null) {
            sql.append(" AND r.position_id = :filterPosition");
        }
        return sql.append(' ').toString();
    }

    MapSqlParameterSource bind(MapSqlParameterSource parameters) {
        if (requisitionId != null) {
            parameters.addValue("filterRequisition", requisitionId);
        }
        if (departmentId != null) {
            parameters.addValue("filterDepartment", departmentId);
        }
        if (positionId != null) {
            parameters.addValue("filterPosition", positionId);
        }
        return parameters;
    }
}
