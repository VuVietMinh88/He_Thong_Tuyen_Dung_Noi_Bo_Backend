package vn.ttcs.recruitment.approval;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Task 256: which levels of a sealed chain apply to a proposed salary.
class ApprovalChainTest {
    private static final List<ApprovalFlowRequest.Step> CHAIN = List.of(step(1, null), step(2, 20_000_000L),
            step(3, 50_000_000L));

    @Test
    void theBaseLevelAlwaysAppliesAndASalaryStrictlyAboveAThresholdAddsItsLevel() {
        assertThat(count(List.of(step(1, null)), 999_999_999_999L)).isEqualTo(1);
        assertThat(count(CHAIN, 0)).isEqualTo(1);
        // Equal to the threshold is not over the limit.
        assertThat(count(CHAIN, 20_000_000L)).isEqualTo(1);
        assertThat(count(CHAIN, 20_000_001L)).isEqualTo(2);
        assertThat(count(CHAIN, 50_000_000L)).isEqualTo(2);
        assertThat(count(CHAIN, 50_000_001L)).isEqualTo(3);
        assertThat(count(CHAIN, 1_000_000_000_000L)).isEqualTo(3);
    }

    @Test
    void levelsWithTheSameThresholdAreAddedTogether() {
        var chain = List.of(step(1, null), step(2, null), step(3, 30_000_000L), step(4, 30_000_000L));

        assertThat(count(chain, 30_000_000L)).isEqualTo(2);
        assertThat(count(chain, 30_000_001L)).isEqualTo(4);
    }

    @Test
    void aChainTheDatabaseWouldNotSealIsABug() {
        assertThatThrownBy(() -> count(List.of(step(1, 10L)), 5)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> count(List.of(step(1, null), step(2, 50L), step(3, 10L)), 20))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> count(List.of(), 20)).isInstanceOf(IllegalStateException.class);
    }

    private static int count(List<ApprovalFlowRequest.Step> steps, long salary) {
        return ApprovalChain.levelCount(steps, BigDecimal.valueOf(salary));
    }

    private static ApprovalFlowRequest.Step step(int position, Long threshold) {
        return new ApprovalFlowRequest.Step(position, threshold == null ? null : BigDecimal.valueOf(threshold), null,
                "HR_MANAGER");
    }
}
