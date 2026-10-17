package vn.ttcs.recruitment.requisition;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

// Task 297: a new RequisitionStatus makes this switch fail to compile until someone decides whether a requisition in
// that status can still be overdue (RequisitionListItemView.OVERDUE_STATUSES, task 298's flag).
class RequisitionOverdueStatusTest {

    @ParameterizedTest
    @EnumSource(RequisitionStatus.class)
    void everyStatusIsClassifiedForOverdue(RequisitionStatus status) {
        boolean overdue = switch (status) {
            case DRAFT, PENDING_APPROVAL, RETURNED, APPROVED -> true;
            case REJECTED, PAUSED, FILLED, CANCELLED -> false;
        };

        assertThat(RequisitionListItemView.OVERDUE_STATUSES.contains(status)).isEqualTo(overdue);
    }
}
