package vn.ttcs.recruitment.jobposting;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import vn.ttcs.recruitment.requisition.RequisitionStatus;

import static org.assertj.core.api.Assertions.assertThat;

// Task 303: a new RequisitionStatus makes this switch fail to compile until someone decides whether job postings may
// be made from, and still changed for, a requisition in that status (JobPostingService.SOURCE_STATUSES).
class JobPostingSourceStatusTest {

    @ParameterizedTest
    @EnumSource(RequisitionStatus.class)
    void everyRequisitionStatusIsClassifiedAsASourceOfPostings(RequisitionStatus status) {
        boolean source = switch (status) {
            case APPROVED -> true;
            case DRAFT, PENDING_APPROVAL, RETURNED, REJECTED, PAUSED, FILLED, CANCELLED -> false;
        };

        assertThat(JobPostingService.SOURCE_STATUSES.contains(status)).isEqualTo(source);
    }
}
