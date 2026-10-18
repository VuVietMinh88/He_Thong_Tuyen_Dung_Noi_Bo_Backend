package vn.ttcs.recruitment.evaluationreport;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.evaluation.EvaluationConclusion;
import vn.ttcs.recruitment.evaluation.EvaluationView;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** One snapshot for summaries and decisions. Callers enforce candidate scope before loading it. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class EvaluationEvidenceService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public EvaluationEvidenceService(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    public record Evidence(UUID latestInterviewId, String hash, List<EvaluationView> submittedForms) { }
    private record Session(UUID id, String status, long version, Instant startsAt, Instant endsAt) { }
    private record Participant(UUID interviewId, UUID userId, boolean active) { }
    private record Snapshot(List<Session> interviews, List<Participant> participants, List<EvaluationView> forms) { }

    /** For a decision, the caller already holds account/session, requisition and application locks. */
    public Evidence load(UUID applicationId, UUID actorId, boolean lock) {
        return load(applicationId, actorId, lock, false);
    }

    /** An ineligible row is an empty result, never an exception that poisons the surrounding comparison transaction. */
    public Evidence loadForComparison(UUID applicationId, UUID actorId) {
        return load(applicationId, actorId, false, true);
    }

    private Evidence load(UUID applicationId, UUID actorId, boolean lock, boolean finalOnly) {
        List<Session> interviews = jdbc.query("""
                SELECT id,status,version,starts_at,ends_at FROM interview_sessions
                WHERE application_id=? ORDER BY id
                """ + (lock ? " FOR UPDATE" : ""), (r, n) -> new Session(r.getObject("id", UUID.class),
                r.getString("status"), r.getLong("version"), instant(r, "starts_at"), instant(r, "ends_at")), applicationId);
        // Evaluation writers lock their interview before the form, so holding every interview prevents new forms too.
        List<EvaluationView> forms = jdbc.query("SELECT * FROM evaluation_forms WHERE application_id=? ORDER BY id"
                + (lock ? " FOR UPDATE" : ""), this::form, applicationId).stream().map(this::items).toList();
        List<Participant> panel = jdbc.query("""
                SELECT p.interview_id,p.user_id,p.active FROM interview_participants p
                JOIN interview_sessions i ON i.id=p.interview_id WHERE i.application_id=?
                ORDER BY p.interview_id,p.user_id
                """, (r, n) -> new Participant(r.getObject("interview_id", UUID.class),
                r.getObject("user_id", UUID.class), r.getBoolean("active")), applicationId);
        List<UUID> scheduled = interviews.stream().filter(i -> "SCHEDULED".equals(i.status())).map(Session::id).toList();
        UUID latest = interviews.stream().filter(i -> "SCHEDULED".equals(i.status()))
                .max(Comparator.comparing(Session::startsAt).thenComparing(i -> i.id().toString())).map(Session::id).orElse(null);
        List<EvaluationView> submitted = forms.stream().filter(f -> "SUBMITTED".equals(f.status()))
                .filter(f -> scheduled.contains(f.interviewId()))
                .filter(f -> !finalOnly || f.interviewId().equals(latest))
                .filter(f -> panel.stream().anyMatch(p -> p.active() && p.interviewId().equals(f.interviewId())
                        && p.userId().equals(f.evaluatorId()))).toList();
        for (UUID interview : scheduled) {
            if (finalOnly && !interview.equals(latest)) continue;
            boolean invited = panel.stream().anyMatch(p -> p.active() && p.interviewId().equals(interview) && p.userId().equals(actorId));
            boolean ownSubmitted = submitted.stream().anyMatch(f -> f.interviewId().equals(interview) && f.evaluatorId().equals(actorId));
            boolean peerDisclosed = submitted.stream().anyMatch(f -> f.interviewId().equals(interview) && !f.evaluatorId().equals(actorId));
            if (invited && !ownSubmitted && peerDisclosed) {
                if (finalOnly) return new Evidence(latest, null, List.of());
                throw new ApiException(HttpStatus.FORBIDDEN, "EVALUATION_BLIND_REVIEW",
                        "Bạn cần nộp phiếu của mình trước khi xem đánh giá của người khác.");
            }
        }
        return new Evidence(latest, finalOnly ? null : hash(new Snapshot(interviews, panel, forms)), submitted);
    }

    private String hash(Snapshot snapshot) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(snapshot))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private EvaluationView form(ResultSet r, int n) throws SQLException {
        String conclusion = r.getString("conclusion");
        return new EvaluationView(r.getObject("id", UUID.class), r.getObject("interview_id", UUID.class),
                r.getObject("application_id", UUID.class), r.getObject("evaluator_id", UUID.class),
                new EvaluationView.Framework(r.getObject("framework_id", UUID.class), r.getString("framework_code"), r.getString("framework_name")),
                r.getString("status"), r.getLong("version"), List.of(), conclusion == null ? null : EvaluationConclusion.valueOf(conclusion),
                r.getBigDecimal("total_score"), instant(r, "first_submitted_at"), instant(r, "submitted_at"),
                instant(r, "created_at"), instant(r, "updated_at"));
    }

    private EvaluationView items(EvaluationView f) {
        List<EvaluationView.Item> items = jdbc.query("SELECT * FROM evaluation_form_items WHERE form_id=? ORDER BY sort_order,criterion_id",
                (r, n) -> new EvaluationView.Item(r.getObject("criterion_id", UUID.class), r.getString("name"), r.getString("description"),
                        r.getBigDecimal("weight"), r.getInt("sort_order"), r.getObject("score", Integer.class), r.getString("comment")), f.id());
        return new EvaluationView(f.id(), f.interviewId(), f.applicationId(), f.evaluatorId(), f.framework(), f.status(), f.version(),
                items, f.conclusion(), f.totalScore(), f.firstSubmittedAt(), f.submittedAt(), f.createdAt(), f.updatedAt());
    }
    private static Instant instant(ResultSet row, String column) throws SQLException {
        var value = row.getTimestamp(column); return value == null ? null : value.toInstant();
    }
}
