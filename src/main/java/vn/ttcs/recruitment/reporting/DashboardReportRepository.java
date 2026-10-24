package vn.ttcs.recruitment.reporting;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Task 555: the dashboard's aggregate statements. Every statement joins the requisition (alias r) and ANDs
 * ReportingAccess.SCOPE (or the interview/offer variant), so no number is ever computed from rows outside the
 * viewer's scope. Counts are SQL aggregates; nothing loads one row per application.
 */
@Repository
class DashboardReportRepository {
    // A requisition that still needs people: approved (recruiting) or paused (on hold, not closed).
    static final List<String> OPEN_STATUSES = List.of("APPROVED", "PAUSED");
    // The stages of an accepted job (HIRED) and of a completed onboarding (ONBOARDED, which follows HIRED).
    static final List<String> HIRED_STAGES = List.of("HIRED", "ONBOARDED");
    // An interview of a rejected or withdrawn application no longer takes place, even if nobody cancelled it.
    static final List<String> CLOSED_APPLICATION_STAGES = List.of("REJECTED", "WITHDRAWN");

    private final NamedParameterJdbcTemplate jdbc;

    DashboardReportRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    record RequisitionCounts(long approved, long paused) { }

    record OverdueRow(UUID requisitionId, UUID positionId, String positionName, UUID departmentId,
                      String departmentName, String status, LocalDate neededBy, int headcount, long hiredCount) { }

    RequisitionCounts requisitionCounts(MapSqlParameterSource parameters) {
        return Objects.requireNonNull(jdbc.queryForObject("""
                SELECT count(*) FILTER (WHERE r.status = 'APPROVED') AS approved,
                       count(*) FILTER (WHERE r.status = 'PAUSED') AS paused
                FROM recruitment_requisitions r
                WHERE r.status IN ('APPROVED', 'PAUSED') AND\s""" + ReportingAccess.SCOPE,
                parameters, (row, number) -> new RequisitionCounts(row.getLong("approved"), row.getLong("paused"))));
    }

    // :receivedFrom inclusive, :receivedTo exclusive.
    long applicationsReceived(MapSqlParameterSource parameters) {
        return count("""
                SELECT count(*)
                FROM job_applications a
                JOIN recruitment_requisitions r ON r.id = a.requisition_id
                WHERE a.received_at >= :receivedFrom AND a.received_at < :receivedTo AND\s""" + ReportingAccess.SCOPE,
                parameters);
    }

    // Scheduled (not cancelled) interviews starting in [:now, :horizon) of applications still in the pipeline.
    long upcomingInterviews(MapSqlParameterSource parameters) {
        return count("""
                SELECT count(*)
                FROM interview_sessions i
                JOIN job_applications a ON a.id = i.application_id
                JOIN recruitment_requisitions r ON r.id = a.requisition_id
                WHERE i.status = 'SCHEDULED' AND i.starts_at >= :now AND i.starts_at < :horizon
                  AND a.stage NOT IN (:closedStages) AND\s""" + ReportingAccess.INTERVIEW_SCOPE,
                parameters.addValue("closedStages", CLOSED_APPLICATION_STAGES));
    }

    // Offers sent to the candidate whose answer is still awaited: SENT and the deadline not passed yet (the expiry job
    // may not have run, so the deadline is checked here too).
    long pendingOffers(MapSqlParameterSource parameters) {
        return count("""
                SELECT count(*)
                FROM offers o
                JOIN job_applications a ON a.id = o.application_id
                JOIN recruitment_requisitions r ON r.id = a.requisition_id
                WHERE o.status = 'SENT' AND o.response_deadline > :now AND\s""" + ReportingAccess.OFFER_SCOPE,
                parameters);
    }

    long overdueCount(MapSqlParameterSource parameters) {
        return count("SELECT count(*) FROM recruitment_requisitions r WHERE" + OVERDUE, parameters);
    }

    // Requisitions that still miss people first, the most overdue first (the earliest needed-by date); then the open
    // requisitions whose headcount is already hired (fully staffed but not closed, a BA/PO question); then by id, so
    // pages never overlap.
    List<OverdueRow> overduePage(MapSqlParameterSource parameters, ReportPaging paging) {
        return jdbc.query("""
                SELECT r.id, r.position_id, p.name AS position_name, r.department_id, d.name AS department_name,
                       r.status, r.needed_by, r.headcount, hired.total AS hired_count
                FROM recruitment_requisitions r
                JOIN positions p ON p.id = r.position_id
                JOIN departments d ON d.id = r.department_id
                CROSS JOIN LATERAL (SELECT count(*) AS total FROM job_applications h
                                    WHERE h.requisition_id = r.id AND h.stage IN (:hiredStages)) hired
                WHERE""" + OVERDUE + "ORDER BY hired.total >= r.headcount, r.needed_by, r.id"
                + " LIMIT :limit OFFSET :offset", parameters.addValue("hiredStages", HIRED_STAGES).addValue("limit", paging.size())
                        .addValue("offset", paging.offset()),
                (row, number) -> new OverdueRow(row.getObject("id", UUID.class),
                        row.getObject("position_id", UUID.class), row.getString("position_name"),
                        row.getObject("department_id", UUID.class), row.getString("department_name"),
                        row.getString("status"), row.getDate("needed_by").toLocalDate(), row.getInt("headcount"),
                        row.getLong("hired_count")));
    }

    // Open, with a needed-by date before the business date :today. A requisition without a date is never overdue.
    private static final String OVERDUE = " r.status IN ('APPROVED', 'PAUSED') AND r.needed_by IS NOT NULL"
            + " AND r.needed_by < :today AND " + ReportingAccess.SCOPE + " ";

    private long count(String sql, MapSqlParameterSource parameters) {
        Long value = jdbc.queryForObject(sql, parameters, Long.class);
        return value == null ? 0 : value;
    }
}
