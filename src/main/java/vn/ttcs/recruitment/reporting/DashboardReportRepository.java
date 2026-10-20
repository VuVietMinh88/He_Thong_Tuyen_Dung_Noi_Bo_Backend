package vn.ttcs.recruitment.reporting;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Tasks 555-557: the dashboard's aggregate statements. Every statement joins the requisition (alias r) and ANDs the
 * ReportQuery's rows (the viewer's scope and the filters), so no number is ever computed from rows outside them.
 * Counts are SQL aggregates; nothing loads one row per application.
 */
@Repository
class DashboardReportRepository {
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

    record ClosedCounts(long filled, long cancelled) { }

    // Approved requisitions are recruiting; paused ones are on hold but not closed.
    RequisitionCounts requisitionCounts(ReportQuery query) {
        return Objects.requireNonNull(jdbc.queryForObject("""
                SELECT count(*) FILTER (WHERE r.status = 'APPROVED') AS approved,
                       count(*) FILTER (WHERE r.status = 'PAUSED') AS paused
                FROM recruitment_requisitions r
                WHERE r.status IN ('APPROVED', 'PAUSED') AND\s""" + query.rows(),
                query.parameters(), (row, number) -> new RequisitionCounts(row.getLong("approved"), row.getLong("paused"))));
    }

    long applicationsReceived(ReportQuery query) {
        return count(applicationsReceivedSql(query), query);
    }

    // :receivedFrom inclusive, :receivedTo exclusive (job_applications_received_at_idx, V67).
    static String applicationsReceivedSql(ReportQuery query) {
        return """
                SELECT count(*)
                FROM job_applications a
                JOIN recruitment_requisitions r ON r.id = a.requisition_id
                WHERE a.received_at >= :receivedFrom AND a.received_at < :receivedTo AND\s""" + query.rows();
    }

    // Scheduled (not cancelled) interviews starting in [:now, :horizon) of applications still in the pipeline.
    long upcomingInterviews(ReportQuery query) {
        query.with("closedStages", CLOSED_APPLICATION_STAGES);
        return count("""
                SELECT count(*)
                FROM interview_sessions i
                JOIN job_applications a ON a.id = i.application_id
                JOIN recruitment_requisitions r ON r.id = a.requisition_id
                WHERE i.status = 'SCHEDULED' AND i.starts_at >= :now AND i.starts_at < :horizon
                  AND a.stage NOT IN (:closedStages) AND\s""" + query.interviewRows(), query);
    }

    // Offers sent to the candidate whose answer is still awaited: SENT and the deadline not passed yet (the expiry job
    // may not have run, so the deadline is checked here too).
    long pendingOffers(ReportQuery query) {
        return count("""
                SELECT count(*)
                FROM offers o
                JOIN job_applications a ON a.id = o.application_id
                JOIN recruitment_requisitions r ON r.id = a.requisition_id
                WHERE o.status = 'SENT' AND o.response_deadline > :now AND\s""" + query.offerRows(), query);
    }

    long overdueCount(ReportQuery query) {
        return count(overdueCountSql(query), query);
    }

    // recruitment_requisitions_open_needed_by_idx (V67) answers it from the partial index.
    static String overdueCountSql(ReportQuery query) {
        return "SELECT count(*) FROM recruitment_requisitions r WHERE" + overdue(query);
    }

    // Requisitions that still miss people first, the most overdue first (the earliest needed-by date); then the open
    // requisitions whose headcount is already hired (fully staffed but not closed, a BA/PO question); then by id, so
    // pages never overlap.
    List<OverdueRow> overduePage(ReportQuery query, ReportPaging paging) {
        return jdbc.query(overduePageSql(query, paging), query.parameters(),
                (row, number) -> new OverdueRow(row.getObject("id", UUID.class),
                        row.getObject("position_id", UUID.class), row.getString("position_name"),
                        row.getObject("department_id", UUID.class), row.getString("department_name"),
                        row.getString("status"), row.getDate("needed_by").toLocalDate(), row.getInt("headcount"),
                        row.getLong("hired_count")));
    }

    // The open requisitions past their needed-by date (recruitment_requisitions_open_needed_by_idx, V67).
    static String overduePageSql(ReportQuery query, ReportPaging paging) {
        query.with("hiredStages", HIRED_STAGES).with("limit", paging.size()).with("offset", paging.offset());
        return """
                SELECT r.id, r.position_id, p.name AS position_name, r.department_id, d.name AS department_name,
                       r.status, r.needed_by, r.headcount, hired.total AS hired_count
                FROM recruitment_requisitions r
                JOIN positions p ON p.id = r.position_id
                JOIN departments d ON d.id = r.department_id
                CROSS JOIN LATERAL (SELECT count(*) AS total FROM job_applications h
                                    WHERE h.requisition_id = r.id AND h.stage IN (:hiredStages)) hired
                WHERE""" + overdue(query) + "ORDER BY hired.total >= r.headcount, r.needed_by, r.id"
                + " LIMIT :limit OFFSET :offset";
    }

    // Task 556: applications received per bucket. :bounds are the moments the buckets start (ascending) and
    // [:seriesFrom, :seriesTo) the whole series, so width_bucket gives 1..n for every counted row.
    Map<Integer, Long> applicationsPerBucket(ReportQuery query) {
        Map<Integer, Long> counts = new HashMap<>();
        jdbc.query(applicationsPerBucketSql(query), query.parameters(), row -> {
            counts.put(row.getInt("bucket"), row.getLong("total"));
        });
        return counts;
    }

    static String applicationsPerBucketSql(ReportQuery query) {
        return """
                SELECT width_bucket(a.received_at, CAST(ARRAY[:bounds] AS timestamptz[])) AS bucket, count(*) AS total
                FROM job_applications a
                JOIN recruitment_requisitions r ON r.id = a.requisition_id
                WHERE a.received_at >= :seriesFrom AND a.received_at < :seriesTo AND\s""" + query.rows()
                + " GROUP BY 1";
    }

    // Task 556: requisitions closed per bucket, by the time of their FILLED or CANCELLED workflow event (append-only,
    // at most one per requisition: requisition_workflow_events_one_end_idx), never by updated_at.
    Map<Integer, ClosedCounts> closedPerBucket(ReportQuery query) {
        Map<Integer, ClosedCounts> counts = new HashMap<>();
        jdbc.query(closedPerBucketSql(query), query.parameters(), row -> {
            counts.put(row.getInt("bucket"), new ClosedCounts(row.getLong("filled"), row.getLong("cancelled")));
        });
        return counts;
    }

    // requisition_workflow_events_closed_at_idx (V67) serves the window.
    static String closedPerBucketSql(ReportQuery query) {
        return """
                SELECT width_bucket(e.occurred_at, CAST(ARRAY[:bounds] AS timestamptz[])) AS bucket,
                       count(*) FILTER (WHERE e.event_type = 'FILLED') AS filled,
                       count(*) FILTER (WHERE e.event_type = 'CANCELLED') AS cancelled
                FROM requisition_workflow_events e
                JOIN recruitment_requisitions r ON r.id = e.requisition_id
                WHERE e.event_type IN ('FILLED', 'CANCELLED')
                  AND e.occurred_at >= :seriesFrom AND e.occurred_at < :seriesTo AND\s""" + query.rows()
                + " GROUP BY 1";
    }

    // Open, with a needed-by date before the business date :today. A requisition without a date is never overdue.
    private static String overdue(ReportQuery query) {
        return " r.status IN ('APPROVED', 'PAUSED') AND r.needed_by IS NOT NULL AND r.needed_by < :today AND "
                + query.rows() + " ";
    }

    private long count(String sql, ReportQuery query) {
        Long value = jdbc.queryForObject(sql, query.parameters(), Long.class);
        return value == null ? 0 : value;
    }
}
