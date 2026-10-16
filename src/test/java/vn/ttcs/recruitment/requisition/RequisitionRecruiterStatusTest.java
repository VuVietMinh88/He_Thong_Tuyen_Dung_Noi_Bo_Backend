package vn.ttcs.recruitment.requisition;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

// Task 284: a new RequisitionStatus makes this switch fail to compile until someone decides whether the recruiters of
// a requisition in that status may still change (RequisitionRecruiterService.ASSIGNABLE_STATUSES).
class RequisitionRecruiterStatusTest {

    @ParameterizedTest
    @EnumSource(RequisitionStatus.class)
    void everyStatusIsClassifiedForRecruiterChanges(RequisitionStatus status) {
        boolean assignable = switch (status) {
            case DRAFT -> true;
        };

        assertThat(RequisitionRecruiterService.ASSIGNABLE_STATUSES.contains(status)).isEqualTo(assignable);
    }
}
