package vn.ttcs.recruitment.hiringdecision;

import com.fasterxml.jackson.annotation.JsonInclude;
import vn.ttcs.recruitment.application.ApplicationStage;

import java.time.Instant;
import java.util.UUID;

/** Decision receipt; the confidential scorecard snapshot stays in the immutable audit ledger. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HiringDecisionView(UUID decisionId, UUID applicationId, UUID interviewId, String decision,
                                 ApplicationStage stage, Instant decidedAt, UUID decidedById, String decidedByName,
                                 String note, UUID rejectionReasonId, String rejectionReasonName, String evidenceHash) {
    @Override public String toString() { return "HiringDecisionView[" + decisionId + ", " + decision + "]"; }
}
