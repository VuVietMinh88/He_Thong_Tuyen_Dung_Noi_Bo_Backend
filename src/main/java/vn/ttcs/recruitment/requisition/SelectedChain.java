package vn.ttcs.recruitment.requisition;

import java.util.List;
import java.util.UUID;

/**
 * Task 256: the approval levels that apply to a requisition, taken from the current sealed version of its department's
 * flow. Task 257 stores (flowId, version, levelCount) as the snapshot of a round: sealed versions never change.
 */
public record SelectedChain(UUID flowId, int version, String name, List<ApprovalLevel> levels) {
    public SelectedChain {
        levels = List.copyOf(levels);
    }

    public int levelCount() {
        return levels.size();
    }
}
