package vn.ttcs.recruitment.evaluation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.ttcs.recruitment.competency.EvaluationCriteria;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class EvaluationRepository {
    private final JdbcTemplate jdbc;
    public EvaluationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    record Interview(UUID id, UUID applicationId, UUID requisitionId, UUID positionId, String status, Instant endsAt) { }

    Optional<Interview> interview(UUID id, boolean lock) {
        return jdbc.query("""
                SELECT i.id,i.application_id,a.requisition_id,r.position_id,i.status,i.ends_at
                FROM interview_sessions i JOIN job_applications a ON a.id=i.application_id
                JOIN recruitment_requisitions r ON r.id=a.requisition_id WHERE i.id=?
                """ + (lock ? " FOR UPDATE OF i" : ""), (row, n) -> new Interview(row.getObject("id", UUID.class),
                row.getObject("application_id", UUID.class), row.getObject("requisition_id", UUID.class),
                row.getObject("position_id", UUID.class), row.getString("status"), row.getTimestamp("ends_at").toInstant()), id)
                .stream().findFirst();
    }

    void lockRequisition(UUID id) {
        jdbc.queryForList("SELECT id FROM recruitment_requisitions WHERE id=? FOR NO KEY UPDATE", UUID.class, id);
    }

    boolean participant(UUID interviewId, UUID actor) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM interview_participants WHERE interview_id=? AND user_id=? AND active)
                """, Boolean.class, interviewId, actor));
    }

    Optional<EvaluationView> own(UUID interview, UUID actor, boolean lock) {
        return jdbc.query("SELECT * FROM evaluation_forms WHERE interview_id=? AND evaluator_id=?"
                        + (lock ? " FOR UPDATE" : ""), this::form, interview, actor)
                .stream().findFirst().map(this::withItems);
    }

    Optional<EvaluationView> find(UUID interview, UUID form, boolean lock) {
        return jdbc.query("SELECT * FROM evaluation_forms WHERE interview_id=? AND id=?"
                        + (lock ? " FOR UPDATE" : ""), this::form, interview, form)
                .stream().findFirst().map(this::withItems);
    }

    // Recruiter status reads deliberately never load scorecard answers, conclusions or totals.
    Optional<EvaluationReopenView> status(UUID interview, UUID form) {
        return jdbc.query("""
                SELECT id,interview_id,status,version,first_submitted_at,updated_at
                FROM evaluation_forms WHERE interview_id=? AND id=?
                """, (row, n) -> new EvaluationReopenView(row.getObject("id", UUID.class),
                row.getObject("interview_id", UUID.class), row.getString("status"), row.getLong("version"),
                instant(row, "first_submitted_at"), instant(row, "updated_at")), interview, form)
                .stream().findFirst();
    }

    List<EvaluationView> peers(UUID interview, UUID actor) {
        return jdbc.query("""
                SELECT f.* FROM evaluation_forms f JOIN interview_participants p
                    ON p.interview_id=f.interview_id AND p.user_id=f.evaluator_id AND p.active
                WHERE f.interview_id=? AND f.evaluator_id<>? AND f.status='SUBMITTED'
                ORDER BY f.submitted_at,f.id
                """, this::form, interview, actor).stream().map(this::withItems).toList();
    }

    UUID create(Interview interview, UUID actor, EvaluationCriteria criteria, Instant now) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO evaluation_forms(id,interview_id,application_id,evaluator_id,framework_id,framework_code,
                    framework_name,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?)
                """, id, interview.id(), interview.applicationId(), actor, criteria.framework().id(), criteria.framework().code(),
                criteria.framework().name(), Timestamp.from(now), Timestamp.from(now));
        for (var item : criteria.criteria()) {
            jdbc.update("""
                    INSERT INTO evaluation_form_items(form_id,criterion_id,name,description,weight,sort_order)
                    VALUES (?,?,?,?,?,?)
                    """, id, item.id(), item.name(), item.description(), item.weight(), item.sortOrder());
        }
        return id;
    }

    void save(EvaluationView form, List<EvaluationAnswerRequest> answers, EvaluationConclusion conclusion,
              BigDecimal total, boolean submit, Instant now) {
        // PUT replaces all draft answers; omission is an explicit clear, never a hidden merge with stale answers.
        jdbc.update("UPDATE evaluation_form_items SET score=NULL,comment=NULL WHERE form_id=?", form.id());
        for (var answer : answers) {
            jdbc.update("UPDATE evaluation_form_items SET score=?,comment=? WHERE form_id=? AND criterion_id=?",
                    answer.score(), answer.comment(), form.id(), answer.criterionId());
        }
        // CASE with only a parameter and NULL needs an explicit timestamp type in PostgreSQL.
        jdbc.update("""
                UPDATE evaluation_forms SET conclusion=?,total_score=?,status=?,version=version+1,updated_at=?,
                    first_submitted_at=CASE WHEN ? THEN COALESCE(first_submitted_at,?) ELSE first_submitted_at END,
                    submitted_at=CASE WHEN ? THEN CAST(? AS TIMESTAMPTZ) ELSE NULL END WHERE id=?
                """, conclusion == null ? null : conclusion.name(), total, submit ? "SUBMITTED" : "DRAFT", Timestamp.from(now),
                submit, Timestamp.from(now), submit, Timestamp.from(now), form.id());
        if (submit) {
            // This survives reopening and resubmitting: once assessed, an appointment can never be rescheduled.
            jdbc.update("UPDATE interview_sessions SET ever_submitted_at=COALESCE(ever_submitted_at,?) WHERE id=?",
                    Timestamp.from(now), form.interviewId());
        }
    }

    void reopen(UUID form, Instant now) {
        jdbc.update("UPDATE evaluation_forms SET status='DRAFT',submitted_at=NULL,version=version+1,updated_at=? WHERE id=?",
                Timestamp.from(now), form);
    }

    void event(EvaluationView form, String kind, UUID actor, Instant now, String reason, String snapshot) {
        jdbc.update("""
                INSERT INTO evaluation_events(id,interview_id,application_id,form_id,event_type,actor_id,occurred_at,
                    reason,submission_snapshot) VALUES (?,?,?,?,?,?,?,?,CAST(? AS jsonb))
                """, UUID.randomUUID(), form.interviewId(), form.applicationId(), form.id(), kind, actor,
                Timestamp.from(now), reason, snapshot);
    }

    private EvaluationView withItems(EvaluationView form) {
        List<EvaluationView.Item> items = jdbc.query("""
                SELECT * FROM evaluation_form_items WHERE form_id=? ORDER BY sort_order,criterion_id
                """, (row, n) -> new EvaluationView.Item(row.getObject("criterion_id", UUID.class), row.getString("name"),
                row.getString("description"), row.getBigDecimal("weight"), row.getInt("sort_order"),
                row.getObject("score", Integer.class), row.getString("comment")), form.id());
        return new EvaluationView(form.id(), form.interviewId(), form.applicationId(), form.evaluatorId(), form.framework(),
                form.status(), form.version(), items, form.conclusion(), form.totalScore(), form.firstSubmittedAt(),
                form.submittedAt(), form.createdAt(), form.updatedAt());
    }

    private EvaluationView form(ResultSet row, int number) throws SQLException {
        String conclusion = row.getString("conclusion");
        return new EvaluationView(row.getObject("id", UUID.class), row.getObject("interview_id", UUID.class),
                row.getObject("application_id", UUID.class), row.getObject("evaluator_id", UUID.class),
                new EvaluationView.Framework(row.getObject("framework_id", UUID.class), row.getString("framework_code"),
                        row.getString("framework_name")), row.getString("status"), row.getLong("version"), List.of(),
                conclusion == null ? null : EvaluationConclusion.valueOf(conclusion), row.getBigDecimal("total_score"),
                instant(row, "first_submitted_at"), instant(row, "submitted_at"), instant(row, "created_at"), instant(row, "updated_at"));
    }
    private static Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column); return value == null ? null : value.toInstant();
    }
}
