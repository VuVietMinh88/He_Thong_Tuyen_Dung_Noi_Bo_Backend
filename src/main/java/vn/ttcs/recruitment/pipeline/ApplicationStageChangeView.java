package vn.ttcs.recruitment.pipeline;

import vn.ttcs.recruitment.application.ApplicationStage;

import java.time.Instant;
import java.util.UUID;

/**
 * Task 380 (story S5-03): the answer of a stage change, which is also the history row it wrote (V34): the stage before
 * and after with their Vietnamese names, when, and who (the caller, as id and full name; never an email). Ids, stages
 * and the actor's name only: no candidate data, so toString needs no redaction.
 */
public record ApplicationStageChangeView(
        UUID applicationId,
        ApplicationStage previousStage,
        String previousStageName,
        ApplicationStage stage,
        String stageName,
        Instant changedAt,
        UUID changedById,
        String changedByName) {

    static ApplicationStageChangeView of(UUID applicationId, ApplicationStage before, ApplicationStage after,
                                         Instant at, UUID actorId, String actorName) {
        return new ApplicationStageChangeView(applicationId, before, before.displayName(), after, after.displayName(),
                at, actorId, actorName);
    }
}
