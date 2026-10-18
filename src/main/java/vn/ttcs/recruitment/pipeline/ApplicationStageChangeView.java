package vn.ttcs.recruitment.pipeline;

import com.fasterxml.jackson.annotation.JsonInclude;
import vn.ttcs.recruitment.application.ApplicationStage;

import java.time.Instant;
import java.util.UUID;

/**
 * Task 380 (story S5-03): the answer of a stage change, which is also the history row it wrote (V34): the stage before
 * and after with their Vietnamese names, when, and who (the caller, as id and full name; never an email). Task 381:
 * a rejection's reason as id and the catalog name stored with it (left out of any other change), and interviewSkipped,
 * true only for the HR exception SCREENING → OFFER. Ids, stages, catalog names and the actor's name only: no candidate
 * data, so toString needs no redaction.
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
        UUID rejectionReasonId,
        String rejectionReasonName,
        boolean interviewSkipped) {

    static ApplicationStageChangeView of(UUID applicationId, ApplicationStage before, ApplicationStage after,
                                         Instant at, UUID actorId, String actorName,
                                         ApplicationStageChangeRepository.RejectionReason reasonOrNull,
                                         boolean interviewSkipped) {
        return new ApplicationStageChangeView(applicationId, before, before.displayName(), after, after.displayName(),
                at, actorId, actorName, reasonOrNull == null ? null : reasonOrNull.id(),
                reasonOrNull == null ? null : reasonOrNull.name(), interviewSkipped);
    }
}
