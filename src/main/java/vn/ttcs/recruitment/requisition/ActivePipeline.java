package vn.ttcs.recruitment.requisition;

import java.util.List;

// Task 291: the candidates still in a requisition's pipeline: count, and, when the source has a breakdown, per stage in
// pipeline order (empty stages = count only). stage and name belong to the source (the candidate module); the
// requisition module adds no stage list of its own. GET /requisitions/{id}/active-pipeline answers exactly these two
// fields, and the 409 REQUISITION_HAS_ACTIVE_CANDIDATES carries them as activePipeline.
public record ActivePipeline(int count, List<Stage> stages) {
    // What a source answers for a requisition nobody is in (and, without a candidate module, for every requisition).
    public static final ActivePipeline NONE = new ActivePipeline(0, List.of());

    public ActivePipeline {
        stages = List.copyOf(stages);
        if (count < 0) {
            throw new IllegalArgumentException("A pipeline count is never negative");
        }
        if (!stages.isEmpty() && stages.stream().mapToLong(Stage::count).sum() != count) {
            throw new IllegalArgumentException("A breakdown must add up to the count");
        }
        if (stages.stream().map(Stage::stage).distinct().count() != stages.size()) {
            throw new IllegalArgumentException("A stage is listed once");
        }
    }

    // One stage of the source's pipeline and how many candidates are in it; a stage nobody is in is not listed.
    public record Stage(String stage, String name, int count) {
        public Stage {
            if (stage == null || stage.isBlank() || name == null || name.isBlank()) {
                throw new IllegalArgumentException("A stage has a code and a name");
            }
            if (count < 1) {
                throw new IllegalArgumentException("Only stages with candidates are listed");
            }
        }
    }
}
