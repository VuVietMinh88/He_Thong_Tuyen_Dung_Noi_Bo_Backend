package vn.ttcs.recruitment.requisition;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static vn.ttcs.recruitment.requisition.RequisitionStatusChange.CANCEL;
import static vn.ttcs.recruitment.requisition.RequisitionStatusChange.FILL;
import static vn.ttcs.recruitment.requisition.RequisitionStatusChange.PAUSE;
import static vn.ttcs.recruitment.requisition.RequisitionStatusChange.RESUME;

// Tasks 293/290: a new RequisitionStatus makes this switch fail to compile until someone decides which status changes
// of HR may leave a requisition in that status (RequisitionStatusChange.from).
class RequisitionStatusChangeTest {

    @ParameterizedTest
    @EnumSource(RequisitionStatus.class)
    void everyStatusIsClassifiedForEveryChange(RequisitionStatus status) {
        Set<RequisitionStatusChange> allowed = switch (status) {
            case DRAFT, PENDING_APPROVAL, RETURNED -> EnumSet.of(CANCEL);
            case APPROVED -> EnumSet.of(PAUSE, FILL, CANCEL);
            case PAUSED -> EnumSet.of(RESUME, FILL, CANCEL);
            case REJECTED, FILLED, CANCELLED -> EnumSet.noneOf(RequisitionStatusChange.class);
        };

        assertThat(Arrays.stream(RequisitionStatusChange.values()).filter(change -> change.allowedFrom(status)))
                .containsExactlyInAnyOrderElementsOf(allowed);
    }

    // Task 291: a new change fails to compile here until someone decides whether it asks for confirmation while
    // candidates are still in the pipeline.
    @ParameterizedTest
    @EnumSource(RequisitionStatusChange.class)
    void onlyFillingAndCancellingCheckTheActivePipeline(RequisitionStatusChange change) {
        boolean checks = switch (change) {
            case FILL, CANCEL -> true;
            case PAUSE, RESUME -> false;
        };

        assertThat(change.checksActivePipeline()).isEqualTo(checks);
    }

    @Test
    void onlyResumingNeedsNoReason() {
        assertThat(Arrays.stream(RequisitionStatusChange.values()).filter(change -> !change.requiresReason()))
                .containsExactly(RESUME);
    }

    @Test
    void eachChangeRecordsItsOwnEventAndTarget() {
        assertThat(Arrays.stream(RequisitionStatusChange.values()).map(RequisitionStatusChange::target))
                .containsExactly(RequisitionStatus.PAUSED, RequisitionStatus.APPROVED, RequisitionStatus.FILLED,
                        RequisitionStatus.CANCELLED);
        for (RequisitionStatusChange change : RequisitionStatusChange.values()) {
            // The history says what happened: resuming returns to APPROVED, but its row is RESUMED.
            String expected = change == RESUME ? "RESUMED" : change.target().name();
            assertThat(change.eventType().name()).as(change.name()).isEqualTo(expected);
        }
        assertThatThrownBy(() -> PAUSE.from().add(RequisitionStatus.DRAFT))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // Task 290: the body's status is validated against TARGETS, so a new change must be added there too.
    @Test
    void targetsPatternListsEveryTarget() {
        assertThat(RequisitionStatusChange.TARGETS).isEqualTo(Arrays.stream(RequisitionStatusChange.values())
                .map(change -> change.target().name()).collect(Collectors.joining("|")));
    }

    @ParameterizedTest
    @EnumSource(RequisitionStatus.class)
    void towardFindsTheChangeOfEachTarget(RequisitionStatus target) {
        RequisitionStatusChange expected = switch (target) {
            case PAUSED -> PAUSE;
            case APPROVED -> RESUME;
            case FILLED -> FILL;
            case CANCELLED -> CANCEL;
            case DRAFT, PENDING_APPROVAL, RETURNED, REJECTED -> null;
        };

        if (expected == null) {
            assertThatThrownBy(() -> RequisitionStatusChange.toward(target))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("No status change leads to " + target);
        } else {
            assertThat(RequisitionStatusChange.toward(target)).isEqualTo(expected);
        }
    }
}
