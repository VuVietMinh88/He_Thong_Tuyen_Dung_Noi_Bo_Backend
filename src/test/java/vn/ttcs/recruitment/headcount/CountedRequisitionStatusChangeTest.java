package vn.ttcs.recruitment.headcount;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import vn.ttcs.recruitment.requisition.RequisitionStatus;
import vn.ttcs.recruitment.requisition.RequisitionStatusChange;

import static org.assertj.core.api.Assertions.assertThat;

// Task 293: no status change of HR leads from a status whose people no longer count against the headcount plan
// (HeadcountPlanRepository.COUNTED_REQUISITION_STATUSES) to one whose people do, so a status change never has to
// lock and check the plan again (task 274).
class CountedRequisitionStatusChangeTest {

    @ParameterizedTest
    @EnumSource(RequisitionStatusChange.class)
    void noStatusChangeCountsPeopleThatWereGivenBack(RequisitionStatusChange change) {
        boolean countedAfter = HeadcountPlanRepository.COUNTED_REQUISITION_STATUSES.contains(change.target().name());
        assertThat(change.from()).isNotEmpty();
        for (RequisitionStatus from : change.from()) {
            boolean countedBefore = HeadcountPlanRepository.COUNTED_REQUISITION_STATUSES.contains(from.name());
            assertThat(!countedBefore && countedAfter).as(change + " from " + from).isFalse();
        }
    }
}
