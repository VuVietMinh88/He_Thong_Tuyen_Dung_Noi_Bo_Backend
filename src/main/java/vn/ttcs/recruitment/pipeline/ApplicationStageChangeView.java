package vn.ttcs.recruitment.pipeline;

import com.fasterxml.jackson.annotation.JsonInclude;
import vn.ttcs.recruitment.application.ApplicationStage;

import java.time.Instant;
import java.util.UUID;

/**
 * Task 380 (story S5-03): the answer of a stage change, which is also the history row it wrote (V34): the stage before
 * and after with their Vietnamese names, when, and who (the caller, as id and full name; never an email). Task 381:
 * a rejection's reason as id and the catalog name stored with it (left out of any other change), and interviewSkipped,
 * true only for the HR exception SCREENING → OFFER. Task 382: the caller's note as stored (left out without one).
 * Staff data only, for the staff member who made the change: no candidate data. toString leaves the note out.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApplicationStageChangeView(
        UUID applicationId,
        ApplicationStage previousStage,
        String previousStageName,
        ApplicationStage stage,
        String stageName,
        Instant changedAt,
        UUID changedById,
        String changedByName,
        String note,
        UUID rejectionReasonId,
        String rejectionReasonName,
        boolean interviewSkipped) {

    static ApplicationStageChangeView of(UUID applicationId, ApplicationStage before, ApplicationStage after,
                                         Instant at, UUID actorId, String actorName, String noteOrNull,
                                         ApplicationStageChangeRepository.RejectionReason reasonOrNull,
                                         boolean interviewSkipped) {
        return new ApplicationStageChangeView(applicationId, before, before.displayName(), after, after.displayName(),
                at, actorId, actorName, noteOrNull, reasonOrNull == null ? null : reasonOrNull.id(),
                reasonOrNull == null ? null : reasonOrNull.name(), interviewSkipped);
    }

    @Override
    public String toString() {
        return "ApplicationStageChangeView[" + applicationId + ", " + previousStage + " -> " + stage + ", note redacted]";
    }
}
