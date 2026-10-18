package vn.ttcs.recruitment.requisition;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

// Task 257: a new RequisitionStatus makes these switches fail to compile until someone decides whether a requisition
// in that status may still be edited and sent for approval (RequisitionService.EDITABLE_STATUSES and
// SUBMITTABLE_STATUSES).
class RequisitionWorkflowStatusTest {

    @ParameterizedTest
    @EnumSource(RequisitionStatus.class)
    void everyStatusIsClassifiedForEditing(RequisitionStatus status) {
        boolean editable = switch (status) {
            case DRAFT, RETURNED -> true;
            case PENDING_APPROVAL, APPROVED, REJECTED -> false;
        };

        assertThat(RequisitionService.EDITABLE_STATUSES.contains(status)).isEqualTo(editable);
    }

    @ParameterizedTest
    @EnumSource(RequisitionStatus.class)
    void everyStatusIsClassifiedForSubmitting(RequisitionStatus status) {
        boolean submittable = switch (status) {
            case DRAFT, RETURNED -> true;
            case PENDING_APPROVAL, APPROVED, REJECTED -> false;
        };

        assertThat(RequisitionService.SUBMITTABLE_STATUSES.contains(status)).isEqualTo(submittable);
    }
}
