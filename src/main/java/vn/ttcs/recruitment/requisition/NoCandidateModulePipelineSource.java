package vn.ttcs.recruitment.requisition;

import org.springframework.stereotype.Component;

import java.util.UUID;

// Task 291: the backend has no candidate module yet (V1–V25 have no candidate or application table), so it knows of
// nobody in any requisition's pipeline: 0 here means "no data", not "checked and empty", and closing is never held
// back. Nothing is invented. The candidate module deletes this class and registers its own RequisitionPipelineSource.
@Component
class NoCandidateModulePipelineSource implements RequisitionPipelineSource {
    @Override
    public ActivePipeline activePipeline(UUID requisitionId) {
        return ActivePipeline.NONE;
    }
}
