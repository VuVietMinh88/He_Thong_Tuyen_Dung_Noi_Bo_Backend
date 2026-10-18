package vn.ttcs.recruitment.hiringdecision;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.evaluationreport.EvaluationEvidenceService.Evidence;

import java.sql.Timestamp;
import java.util.UUID;

/** Joins the pipeline transaction: a decision cannot commit without its stage and history event. */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class HiringDecisionRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public HiringDecisionRepository(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    public UUID departmentOf(UUID requisitionId) {
        return jdbc.queryForObject("SELECT department_id FROM recruitment_requisitions WHERE id=?", UUID.class,
                requisitionId);
    }

    public boolean requisitionActive(UUID requisitionId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT status='APPROVED' FROM recruitment_requisitions WHERE id=?", Boolean.class, requisitionId));
    }

    public void record(HiringDecisionView decision, UUID stageEventId, Evidence evidence) {
        jdbc.update("""
                INSERT INTO hiring_decisions(id,application_id,interview_id,stage_event_id,decision,actor_id,
                    actor_name,decided_at,note,rejection_reason_id,rejection_reason_name,evidence_hash,evidence_snapshot)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,CAST(? AS jsonb))
                """, decision.decisionId(), decision.applicationId(), decision.interviewId(), stageEventId,
                decision.decision(), decision.decidedById(), decision.decidedByName(), Timestamp.from(decision.decidedAt()),
                decision.note(), decision.rejectionReasonId(), decision.rejectionReasonName(), evidence.hash(),
                json.writeValueAsString(evidence.submittedForms()));
    }
}
