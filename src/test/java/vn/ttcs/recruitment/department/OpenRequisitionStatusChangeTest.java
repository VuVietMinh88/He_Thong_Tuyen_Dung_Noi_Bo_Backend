package vn.ttcs.recruitment.department;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import vn.ttcs.recruitment.requisition.RequisitionStatus;
import vn.ttcs.recruitment.requisition.RequisitionStatusChange;

import static org.assertj.core.api.Assertions.assertThat;

// Task 293: no status change of HR leads from a closed status to an open one
// (DepartmentRepository.OPEN_REQUISITION_STATUSES), so a department deletion that saw no open requisition stays
// right (task 197).
class OpenRequisitionStatusChangeTest {

    @ParameterizedTest
    @EnumSource(RequisitionStatusChange.class)
    void noStatusChangeReopensAClosedRequisition(RequisitionStatusChange change) {
        boolean openAfter = DepartmentRepository.OPEN_REQUISITION_STATUSES.contains(change.target().name());
        assertThat(change.from()).isNotEmpty();
        for (RequisitionStatus from : change.from()) {
            boolean openBefore = DepartmentRepository.OPEN_REQUISITION_STATUSES.contains(from.name());
            assertThat(!openBefore && openAfter).as(change + " from " + from).isFalse();
        }
    }
}
