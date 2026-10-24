package vn.ttcs.recruitment.reporting;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.ttcs.recruitment.reporting.RequisitionProgressView.AwaitingDecision;
import vn.ttcs.recruitment.reporting.RequisitionProgressView.PendingEvaluation;
import vn.ttcs.recruitment.reporting.RequisitionProgressView.UpcomingInterview;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tasks 562-563: the statements behind requisition progress. The requisitions were already checked against the
 * viewer's scope (ReportingAccess.requireFilters for one, the page query for a list); every statement still ANDs the
 * ReportQuery's rows, so a number or a list can never reach outside it. :requisitionIds is the one requisition of the
 * detail or the at most 50 requisitions of a page, so the counts of a page are four grouped statements, not one per
 * row. Interview lists keep only applications still in the pipeline (NEW..OFFER): a rejected, withdrawn or hired
 * candidate is waiting for nobody.
 */
@Repository
class ProgressReportRepository {
    static final List<String> PIPELINE_STAGES = List.of("NEW", "SCREENING", "INTERVIEW", "OFFER");

    private final NamedParameterJdbcTemplate jdbc;

    ProgressReportRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    record Header(UUID requisitionId, UUID positionId, String positionName, UUID departmentId, String departmentName,
                  String status, int headcount, LocalDate neededBy) { }

    /** A list row with the candidate it names, for the disclosure log. */
    record Disclosed<T>(UUID candidateId, UUID applicationId, T item) { }

    private static final String HEADER = """
            SELECT r.id, r.position_id, p.name AS position_name, r.department_id, d.name AS department_name,
                   r.status, r.headcount, r.needed_by
            FROM recruitment_requisitions r
            JOIN positions p ON p.id = r.position_id
            JOIN departments d ON d.id = r.department_id
            """;

    Header header(ReportQuery query) {
        return jdbc.query(HEADER + "WHERE r.id IN (:requisitionIds) AND " + query.rows(), query.parameters(),
                (row, number) -> header(row)).getFirst();
    }

    /** Per requisition, the number of applications in each current stage (stages without one are absent). */
    Map<UUID, Map<String, Long>> stageCounts(ReportQuery query) {
        Map<UUID, Map<String, Long>> counts = new HashMap<>();
        jdbc.query("""
                SELECT a.requisition_id, a.stage, count(*) AS total
                FROM job_applications a
                JOIN recruitment_requisitions r ON r.id = a.requisition_id
                WHERE a.requisition_id IN (:requisitionIds) AND\s""" + query.rows() + " GROUP BY 1, 2",
                query.parameters(), row -> {
            counts.computeIfAbsent(row.getObject("requisition_id", UUID.class), ignored -> new HashMap<>())
                    .put(row.getString("stage"), row.getLong("total"));
        });
        return counts;
    }

    // Scheduled interviews that have not started yet.
    private static final String UPCOMING = """
             FROM interview_sessions i
             JOIN job_applications a ON a.id = i.application_id
             JOIN recruitment_requisitions r ON r.id = a.requisition_id
             JOIN candidate_applications ca ON ca.application_id = a.id
             WHERE a.requisition_id IN (:requisitionIds) AND i.status = 'SCHEDULED' AND i.starts_at >= :now
               AND a.stage IN (:pipelineStages) AND\s""";

    Map<UUID, Long> upcomingCounts(ReportQuery query) {
        query.with("pipelineStages", PIPELINE_STAGES);
        return grouped("SELECT a.requisition_id, count(*) AS total " + UPCOMING + query.interviewRows()
                + " GROUP BY 1", query);
    }

    // Soonest first.
    List<Disclosed<UpcomingInterview>> upcoming(ReportQuery query, int limit) {
        query.with("pipelineStages", PIPELINE_STAGES).with("limit", limit);
        return jdbc.query("""
                SELECT i.id, i.application_id, ca.candidate_id, a.full_name, a.stage, i.round_name, i.starts_at,
                       i.ends_at, i.mode,
                       EXISTS (SELECT 1 FROM interview_participants p
                               WHERE p.interview_id = i.id AND p.user_id = :assignee AND p.active) AS on_panel
                """ + UPCOMING + query.interviewRows() + " ORDER BY i.starts_at, i.id LIMIT :limit",
                query.parameters(), (row, number) -> new Disclosed<>(row.getObject("candidate_id", UUID.class),
                        row.getObject("application_id", UUID.class),
                        new UpcomingInterview(row.getObject("id", UUID.class), row.getObject("application_id", UUID.class),
                                row.getString("full_name"), row.getString("stage"), row.getString("round_name"),
                                instant(row, "starts_at"), instant(row, "ends_at"), row.getString("mode"),
                                row.getBoolean("on_panel"))));
    }

    // The viewer's own evaluation work: an interview that has ended, not cancelled, where the viewer is an active
    // panel member and has no submitted form (none yet, or a draft). Never another evaluator's form.
    private static final String PENDING = """
             FROM interview_sessions i
             JOIN interview_participants p ON p.interview_id = i.id AND p.user_id = :assignee AND p.active
             JOIN job_applications a ON a.id = i.application_id
             JOIN recruitment_requisitions r ON r.id = a.requisition_id
             JOIN candidate_applications ca ON ca.application_id = a.id
             LEFT JOIN evaluation_forms f ON f.interview_id = i.id AND f.evaluator_id = :assignee
             WHERE a.requisition_id IN (:requisitionIds) AND i.status = 'SCHEDULED' AND i.ends_at <= :now
               AND (f.id IS NULL OR f.status <> 'SUBMITTED') AND a.stage IN (:pipelineStages) AND\s""";

    Map<UUID, Long> pendingCounts(ReportQuery query) {
        query.with("pipelineStages", PIPELINE_STAGES);
        return grouped("SELECT a.requisition_id, count(*) AS total " + PENDING + query.rows() + " GROUP BY 1", query);
    }

    // Longest waiting first.
    List<Disclosed<PendingEvaluation>> pending(ReportQuery query, int limit) {
        query.with("pipelineStages", PIPELINE_STAGES).with("limit", limit);
        return jdbc.query("SELECT i.id, i.application_id, ca.candidate_id, a.full_name, i.round_name, i.ends_at,"
                        + " f.status AS form_status " + PENDING + query.rows() + " ORDER BY i.ends_at, i.id LIMIT :limit",
                query.parameters(), (row, number) -> new Disclosed<>(row.getObject("candidate_id", UUID.class),
                        row.getObject("application_id", UUID.class),
                        new PendingEvaluation(row.getObject("id", UUID.class), row.getObject("application_id", UUID.class),
                                row.getString("full_name"), row.getString("round_name"), instant(row, "ends_at"),
                                row.getString("form_status") == null ? "NOT_STARTED" : row.getString("form_status"))));
    }

    // Applications ready for a hiring decision, as ApplicationStageChangeService.decide accepts them: stage INTERVIEW,
    // the requisition APPROVED, and at least one form SUBMITTED by an active panel member for the latest scheduled
    // interview (EvaluationEvidenceService: the latest start time, then the higher id as text). A decision moves the
    // stage to OFFER or REJECTED, so a decided application leaves this list. decisionScope is TRUE for ADMIN and
    // HR_MANAGER and the managed-department condition for a HIRING_MANAGER, the rule of requireDecisionScope.
    private static String awaiting(String decisionScope) {
        return """
                 FROM job_applications a
                 JOIN recruitment_requisitions r ON r.id = a.requisition_id
                 JOIN candidate_applications ca ON ca.application_id = a.id
                 JOIN LATERAL (
                     SELECT i.id, i.round_name, i.ends_at FROM interview_sessions i
                     WHERE i.application_id = a.id AND i.status = 'SCHEDULED'
                     ORDER BY i.starts_at DESC, CAST(i.id AS text) DESC LIMIT 1) latest ON TRUE
                 JOIN LATERAL (
                     SELECT count(*) AS submitted FROM evaluation_forms f
                     JOIN interview_participants p ON p.interview_id = f.interview_id AND p.user_id = f.evaluator_id
                         AND p.active
                     WHERE f.interview_id = latest.id AND f.status = 'SUBMITTED') forms ON forms.submitted > 0
                 WHERE a.requisition_id IN (:requisitionIds) AND a.stage = 'INTERVIEW' AND r.status = 'APPROVED'
                   AND\s""" + decisionScope + " AND ";
    }

    Map<UUID, Long> awaitingCounts(ReportQuery query, String decisionScope) {
        return grouped("SELECT a.requisition_id, count(*) AS total " + awaiting(decisionScope) + query.rows()
                + " GROUP BY 1", query);
    }

    // Longest waiting first.
    List<Disclosed<AwaitingDecision>> awaiting(ReportQuery query, String decisionScope, int limit) {
        query.with("limit", limit);
        return jdbc.query("SELECT a.id, ca.candidate_id, a.full_name, latest.id AS interview_id, latest.round_name,"
                        + " latest.ends_at, forms.submitted " + awaiting(decisionScope) + query.rows()
                        + " ORDER BY latest.ends_at, a.id LIMIT :limit",
                query.parameters(), (row, number) -> new Disclosed<>(row.getObject("candidate_id", UUID.class),
                        row.getObject("id", UUID.class),
                        new AwaitingDecision(row.getObject("id", UUID.class), row.getString("full_name"),
                                row.getObject("interview_id", UUID.class), row.getString("round_name"),
                                instant(row, "ends_at"), row.getLong("submitted"))));
    }

    private static Header header(ResultSet row) throws SQLException {
        return new Header(row.getObject("id", UUID.class), row.getObject("position_id", UUID.class),
                row.getString("position_name"), row.getObject("department_id", UUID.class),
                row.getString("department_name"), row.getString("status"), row.getInt("headcount"),
                row.getDate("needed_by") == null ? null : row.getDate("needed_by").toLocalDate());
    }

    private Map<UUID, Long> grouped(String sql, ReportQuery query) {
        Map<UUID, Long> counts = new HashMap<>();
        jdbc.query(sql, query.parameters(), row -> {
            counts.put(row.getObject("requisition_id", UUID.class), row.getLong("total"));
        });
        return counts;
    }

    private long count(String sql, ReportQuery query) {
        Long value = jdbc.queryForObject(sql, query.parameters(), Long.class);
        return value == null ? 0 : value;
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        return row.getTimestamp(column).toInstant();
    }
}
