package vn.ttcs.recruitment.interviewquestionusage;

import org.springframework.jdbc.core.JdbcTemplate;
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
public class InterviewQuestionUsageRepository {
    private final JdbcTemplate jdbc;
    public InterviewQuestionUsageRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    record Interview(UUID id, UUID applicationId, UUID requisitionId, UUID positionId, String status,
                     String stage, String requisitionStatus, long version, String roundName, Instant startsAt) { }
    record Form(UUID id, String status, Instant firstSubmittedAt) { }

    Optional<Interview> interview(UUID id, boolean lock) {
        return jdbc.query("""
                SELECT i.*, a.requisition_id, a.stage, r.position_id, r.status AS requisition_status
                FROM interview_sessions i JOIN job_applications a ON a.id=i.application_id
                JOIN recruitment_requisitions r ON r.id=a.requisition_id WHERE i.id=?
                """ + (lock ? " FOR UPDATE OF i" : ""), (row, n) -> new Interview(id,
                row.getObject("application_id", UUID.class), row.getObject("requisition_id", UUID.class),
                row.getObject("position_id", UUID.class), row.getString("status"), row.getString("stage"),
                row.getString("requisition_status"), row.getLong("version"), row.getString("round_name"),
                row.getTimestamp("starts_at").toInstant()), id).stream().findFirst();
    }
    void lockParents(Interview interview) {
        jdbc.queryForList("SELECT id FROM recruitment_requisitions WHERE id=? FOR NO KEY UPDATE", UUID.class, interview.requisitionId());
        jdbc.queryForList("SELECT id FROM job_applications WHERE id=? FOR NO KEY UPDATE", UUID.class, interview.applicationId());
    }
    boolean participant(UUID interview, UUID actor) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM interview_participants WHERE interview_id=? AND user_id=? AND active)
                """, Boolean.class, interview, actor));
    }
    Optional<Form> form(UUID interview, UUID actor, boolean lock) {
        return jdbc.query("SELECT id,status,first_submitted_at FROM evaluation_forms WHERE interview_id=? AND evaluator_id=?"
                + (lock ? " FOR UPDATE" : ""), (row, n) -> new Form(row.getObject("id", UUID.class),
                row.getString("status"), instant(row, "first_submitted_at")), interview, actor).stream().findFirst();
    }

    // Asked questions keep their original wording even when their live bank row is deactivated, moved or deleted.
    private static final String CURRENT = """
            WITH available AS (
                SELECT q.id AS question_id, q.criterion_id, f.name AS criterion_name,
                    q.content,q.answer_hint,q.difficulty,false AS asked,NULL::uuid AS asked_by,NULL::timestamptz AS asked_at
                FROM interview_questions q JOIN evaluation_form_items f ON f.criterion_id=q.criterion_id AND f.form_id=?
                WHERE q.active AND NOT EXISTS (SELECT 1 FROM interview_question_usage u WHERE u.interview_id=? AND u.question_id=q.id)
                UNION ALL
                SELECT question_id,criterion_id,criterion_name,content,answer_hint,difficulty,true,asked_by,asked_at
                FROM interview_question_usage WHERE interview_id=?
            )
            """;
    InterviewQuestionUsageView.Page<InterviewQuestionUsageView> questions(UUID form, UUID interview, UUID criterion, int page, int size) {
        String filter = criterion == null ? "" : " WHERE criterion_id=?";
        var args = new java.util.ArrayList<Object>(List.of(form, interview, interview));
        if (criterion != null) args.add(criterion);
        long count = jdbc.queryForObject(CURRENT + "SELECT count(*) FROM available" + filter, Long.class, args.toArray());
        args.add(size); args.add((long) page * size);
        var items = jdbc.query(CURRENT + "SELECT * FROM available" + filter
                + " ORDER BY criterion_name,criterion_id,question_id LIMIT ? OFFSET ?", this::question, args.toArray());
        return new InterviewQuestionUsageView.Page<>(items, page, size, count, (count + size - 1) / size);
    }
    Optional<InterviewQuestionUsageView> asked(UUID interview, UUID question) {
        return jdbc.query("SELECT *,true AS asked FROM interview_question_usage WHERE interview_id=? AND question_id=?",
                this::question, interview, question).stream().findFirst();
    }
    Optional<InterviewQuestionUsageView> eligibleQuestion(UUID form, UUID question) {
        return jdbc.query("""
                SELECT q.id AS question_id,q.criterion_id,f.name AS criterion_name,q.content,q.answer_hint,q.difficulty,
                    false AS asked,NULL::uuid AS asked_by,NULL::timestamptz AS asked_at
                FROM interview_questions q JOIN evaluation_form_items f ON f.criterion_id=q.criterion_id AND f.form_id=?
                WHERE q.id=? AND q.active FOR SHARE OF q
                """, this::question, form, question).stream().findFirst();
    }
    void mark(Interview interview, InterviewQuestionUsageView question, UUID actor, Instant now) {
        jdbc.update("""
                INSERT INTO interview_question_usage(interview_id,application_id,question_id,criterion_id,criterion_name,
                    content,answer_hint,difficulty,round_name,interview_starts_at,asked_by,asked_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """, interview.id(), interview.applicationId(), question.questionId(), question.criterionId(), question.criterionName(),
                question.content(), question.answerHint(), question.difficulty(), interview.roundName(),
                Timestamp.from(interview.startsAt()), actor, Timestamp.from(now));
    }
    InterviewQuestionUsageView.Page<InterviewQuestionUsageView.History> history(Interview interview, UUID criterion, int page, int size) {
        String source = """
                FROM interview_question_usage u JOIN interview_sessions i ON i.id=u.interview_id
                WHERE u.application_id=? AND u.interview_id<>? AND i.starts_at<? AND i.ends_at<=? AND i.status='SCHEDULED'
                """ + (criterion == null ? "" : " AND u.criterion_id=?");
        var args = new java.util.ArrayList<Object>(List.of(interview.applicationId(), interview.id(),
                Timestamp.from(interview.startsAt()), Timestamp.from(interview.startsAt())));
        if (criterion != null) args.add(criterion);
        long count = jdbc.queryForObject("SELECT count(*) " + source, Long.class, args.toArray());
        args.add(size); args.add((long) page * size);
        var items = jdbc.query("SELECT u.*,true AS asked " + source
                + " ORDER BY u.interview_starts_at,u.interview_id,u.asked_at,u.question_id LIMIT ? OFFSET ?",
                (row, n) -> new InterviewQuestionUsageView.History(row.getObject("interview_id", UUID.class), row.getString("round_name"),
                        row.getTimestamp("interview_starts_at").toInstant(), question(row, n)), args.toArray());
        return new InterviewQuestionUsageView.Page<>(items, page, size, count, (count + size - 1) / size);
    }
    private InterviewQuestionUsageView question(ResultSet row, int number) throws SQLException {
        return new InterviewQuestionUsageView(row.getObject("question_id", UUID.class), row.getObject("criterion_id", UUID.class),
                row.getString("criterion_name"), row.getString("content"), row.getString("answer_hint"), row.getString("difficulty"),
                row.getBoolean("asked"), row.getObject("asked_by", UUID.class), instant(row, "asked_at"));
    }
    private static Instant instant(ResultSet row, String field) throws SQLException {
        Timestamp value = row.getTimestamp(field); return value == null ? null : value.toInstant();
    }
}
