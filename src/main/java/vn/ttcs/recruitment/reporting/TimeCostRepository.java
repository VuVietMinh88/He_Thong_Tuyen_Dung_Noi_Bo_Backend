package vn.ttcs.recruitment.reporting;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tasks 574-576: the statements of the time and cost report. Hires and costs are read separately and combined by
 * requisition in Java, never joined row to row (a join of costs to hires would count each cost once per hire).
 */
@Repository
class TimeCostRepository {
    private final NamedParameterJdbcTemplate jdbc;

    TimeCostRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    record HireRow(UUID applicationId, UUID requisitionId, Instant hiredAt, boolean acceptedOffer, boolean onboarded) { }

    record CostRow(UUID requisitionId, String costType, long totalVnd, long entries) { }

    record RequisitionInfo(UUID requisitionId, UUID departmentId, String departmentName, UUID positionId,
                           String positionName, String status) { }

    // The first time each application entered HIRED, when that moment is in [:fromTs, :toTs) (V67_2 index). Accepted
    // offer: the HIRED event came from the candidate's answer (offer_id) or the application has an ACCEPTED offer.
    // Onboarded: it went on to ONBOARDED. At most :limit rows, so an oversized report is refused, not loaded.
    List<HireRow> hires(ReportQuery query, int limit) {
        query.with("limit", limit);
        return jdbc.query("""
                SELECT a.id, a.requisition_id, e.occurred_at,
                       (e.offer_id IS NOT NULL OR EXISTS (SELECT 1 FROM offers o
                            WHERE o.application_id = a.id AND o.status = 'ACCEPTED')) AS accepted_offer,
                       EXISTS (SELECT 1 FROM application_stage_events x
                               WHERE x.application_id = a.id AND x.stage_after = 'ONBOARDED') AS onboarded
                FROM application_stage_events e
                JOIN job_applications a ON a.id = e.application_id
                JOIN recruitment_requisitions r ON r.id = a.requisition_id
                WHERE e.stage_after = 'HIRED' AND e.occurred_at >= :fromTs AND e.occurred_at < :toTs
                  AND NOT EXISTS (SELECT 1 FROM application_stage_events p
                                  WHERE p.application_id = e.application_id AND p.stage_after = 'HIRED'
                                    AND (p.occurred_at < e.occurred_at OR (p.occurred_at = e.occurred_at AND p.id < e.id)))
                  AND\s""" + query.rows() + " LIMIT :limit", query.parameters(),
                (row, number) -> new HireRow(row.getObject("id", UUID.class), row.getObject("requisition_id", UUID.class),
                        row.getTimestamp("occurred_at").toInstant(), row.getBoolean("accepted_offer"),
                        row.getBoolean("onboarded")));
    }

    // Costs not voided, incurred between :fromDate and :toDate (inclusive), per requisition and type.
    List<CostRow> costs(ReportQuery query) {
        return jdbc.query("""
                SELECT c.requisition_id, c.cost_type, sum(c.amount_vnd) AS total, count(*) AS entries
                FROM recruitment_costs c
                JOIN recruitment_requisitions r ON r.id = c.requisition_id
                WHERE c.voided_at IS NULL AND c.incurred_on BETWEEN :fromDate AND :toDate AND\s""" + query.rows()
                        + " GROUP BY 1, 2", query.parameters(),
                (row, number) -> new CostRow(row.getObject("requisition_id", UUID.class), row.getString("cost_type"),
                        row.getLong("total"), row.getLong("entries")));
    }

    // The final approval of each requisition: the first APPROVED event that made it APPROVED (an intermediate level
    // leaves it PENDING_APPROVAL; RESUMED after a pause is not an approval).
    Map<UUID, Instant> finalApprovals(Collection<UUID> requisitionIds) {
        Map<UUID, Instant> approvals = new HashMap<>();
        if (requisitionIds.isEmpty()) {
            return approvals;
        }
        jdbc.query("""
                SELECT requisition_id, min(occurred_at) AS approved_at FROM requisition_workflow_events
                WHERE event_type = 'APPROVED' AND status_after = 'APPROVED' AND requisition_id IN (:ids)
                GROUP BY requisition_id
                """, new MapSqlParameterSource("ids", requisitionIds), row -> {
            approvals.put(row.getObject("requisition_id", UUID.class), row.getTimestamp("approved_at").toInstant());
        });
        return approvals;
    }

    List<RequisitionInfo> requisitions(Collection<UUID> requisitionIds) {
        if (requisitionIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                SELECT r.id, r.department_id, d.name AS department_name, r.position_id, p.name AS position_name, r.status
                FROM recruitment_requisitions r
                JOIN departments d ON d.id = r.department_id
                JOIN positions p ON p.id = r.position_id
                WHERE r.id IN (:ids)
                """, new MapSqlParameterSource("ids", requisitionIds),
                (row, number) -> new RequisitionInfo(row.getObject("id", UUID.class),
                        row.getObject("department_id", UUID.class), row.getString("department_name"),
                        row.getObject("position_id", UUID.class), row.getString("position_name"), row.getString("status")));
    }
}
