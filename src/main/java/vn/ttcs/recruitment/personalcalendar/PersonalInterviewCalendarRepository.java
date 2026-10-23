package vn.ttcs.recruitment.personalcalendar;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
class PersonalInterviewCalendarRepository {
    private static final String FROM = """
            FROM interview_sessions i
            JOIN interview_participants panel ON panel.interview_id=i.id AND panel.user_id=:actor AND panel.active
            JOIN job_applications a ON a.id=i.application_id
            JOIN candidate_applications ca ON ca.application_id=a.id
            JOIN recruitment_requisitions r ON r.id=a.requisition_id
            JOIN positions pos ON pos.id=r.position_id
            LEFT JOIN evaluation_forms f ON f.interview_id=i.id AND f.evaluator_id=:actor
            """;
    private static final String WHERE = """
            WHERE i.status='SCHEDULED' AND i.ends_at>:from AND i.starts_at<:to
              AND a.stage IN ('NEW','SCREENING','INTERVIEW') AND r.status='APPROVED'
              AND (:history OR i.ends_at>:now OR (:pending AND (f.status IS NULL OR f.status<>'SUBMITTED')))
            """;
    private final NamedParameterJdbcTemplate jdbc;

    PersonalInterviewCalendarRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    Page page(UUID actor, Instant from, Instant to, Instant now, boolean pending, boolean history, int page, int size) {
        var parameters = new MapSqlParameterSource("actor", actor).addValue("from", Timestamp.from(from))
                .addValue("to", Timestamp.from(to)).addValue("now", Timestamp.from(now))
                .addValue("pending", pending).addValue("history", history)
                .addValue("limit", size).addValue("offset", (long) page * size);
        Long total = jdbc.queryForObject("SELECT count(*) " + FROM + WHERE, parameters, Long.class);
        var rows = jdbc.query("""
                SELECT i.id,i.application_id,ca.candidate_id,a.full_name,pos.id AS position_id,pos.name AS position_name,
                       i.round_name,i.starts_at,i.ends_at,i.mode,i.room,i.meeting_url,f.status AS form_status,
                       f.first_submitted_at,
                       EXISTS (SELECT 1 FROM application_cvs cv WHERE cv.application_id=a.id) AS has_cv
                """ + FROM + WHERE + " ORDER BY i.starts_at,i.id LIMIT :limit OFFSET :offset",
                parameters, (row, number) -> row(row, now));
        return new Page(rows, total == null ? 0 : total);
    }

    Optional<CvContext> cvContext(UUID interview, UUID actor) {
        return jdbc.query("""
                SELECT i.application_id,ca.candidate_id,i.status,
                       (a.stage IN ('NEW','SCREENING','INTERVIEW') AND r.status='APPROVED') AS preparation_active,
                       EXISTS (SELECT 1 FROM interview_participants p
                               WHERE p.interview_id=i.id AND p.user_id=:actor AND p.active) AS invited,
                       cv.filename,cv.content_type
                FROM interview_sessions i
                JOIN job_applications a ON a.id=i.application_id
                JOIN recruitment_requisitions r ON r.id=a.requisition_id
                JOIN candidate_applications ca ON ca.application_id=i.application_id
                LEFT JOIN application_cvs cv ON cv.application_id=i.application_id
                WHERE i.id=:id
                """, new MapSqlParameterSource("id", interview).addValue("actor", actor),
                (row, number) -> new CvContext(row.getObject("application_id", UUID.class),
                        row.getObject("candidate_id", UUID.class), row.getBoolean("invited"),
                        "SCHEDULED".equals(row.getString("status")), row.getBoolean("preparation_active"), row.getString("filename"),
                        row.getString("content_type"))).stream().findFirst();
    }

    byte[] cvContent(UUID application) {
        return jdbc.queryForObject("SELECT content FROM application_cvs WHERE application_id=:id",
                new MapSqlParameterSource("id", application), byte[].class);
    }

    private static Row row(ResultSet row, Instant now) throws SQLException {
        String status = row.getString("form_status");
        var state = status == null ? PersonalInterviewCalendar.EvaluationState.NOT_STARTED
                : "SUBMITTED".equals(status) ? PersonalInterviewCalendar.EvaluationState.SUBMITTED
                : row.getTimestamp("first_submitted_at") != null ? PersonalInterviewCalendar.EvaluationState.REOPENED
                : PersonalInterviewCalendar.EvaluationState.DRAFT;
        UUID id = row.getObject("id", UUID.class);
        Instant end = row.getTimestamp("ends_at").toInstant();
        boolean cv = row.getBoolean("has_cv");
        var item = new PersonalInterviewCalendar.Item(id, row.getObject("application_id", UUID.class),
                row.getString("full_name"), row.getObject("position_id", UUID.class), row.getString("position_name"),
                row.getString("round_name"), row.getTimestamp("starts_at").toInstant(), end,
                row.getString("mode"), row.getString("room"), row.getString("meeting_url"), state,
                state == PersonalInterviewCalendar.EvaluationState.SUBMITTED,
                !end.isAfter(now) && state != PersonalInterviewCalendar.EvaluationState.SUBMITTED,
                cv, cv ? "/api/v1/interviews/" + id + "/cv" : null);
        return new Row(row.getObject("candidate_id", UUID.class), item);
    }

    record Row(UUID candidateId, PersonalInterviewCalendar.Item item) { }
    record Page(List<Row> rows, long total) { }
    record CvContext(UUID applicationId, UUID candidateId, boolean invited, boolean scheduled, boolean preparationActive,
                     String filename, String contentType) {
        @Override public String toString() { return "CvContext[application=" + applicationId + ", filename redacted]"; }
    }
}
