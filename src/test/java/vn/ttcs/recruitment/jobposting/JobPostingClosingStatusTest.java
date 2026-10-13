package vn.ttcs.recruitment.jobposting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import vn.ttcs.recruitment.requisition.RequisitionStatus;

import static org.assertj.core.api.Assertions.assertThat;

// Task 292: a new RequisitionStatus makes the first switch fail to compile until someone decides whether reaching it
// closes the requisition's postings (JobPostingRequisitionSync.CLOSING_STATUSES), and a new JobPostingStatus the
// second until someone decides whether a posting in it is closed then (CLOSABLE_STATUSES).
class JobPostingClosingStatusTest {

    @ParameterizedTest
    @EnumSource(RequisitionStatus.class)
    void onlyAFilledOrCancelledRequisitionClosesItsPostings(RequisitionStatus status) {
        boolean closing = switch (status) {
            case FILLED, CANCELLED -> true;
            case DRAFT, PENDING_APPROVAL, RETURNED, APPROVED, REJECTED, PAUSED -> false;
        };

        assertThat(JobPostingRequisitionSync.CLOSING_STATUSES.contains(status)).isEqualTo(closing);
    }

    @ParameterizedTest
    @EnumSource(JobPostingStatus.class)
    void everyPostingThatIsNotClosedYetIsClosed(JobPostingStatus status) {
        boolean closable = switch (status) {
            case DRAFT, PENDING_APPROVAL, REVISION_REQUESTED, APPROVED, PUBLISHED -> true;
            case CLOSED -> false;
        };

        assertThat(JobPostingRequisitionSync.CLOSABLE_STATUSES.contains(status)).isEqualTo(closable);
    }

    // A requisition that closes its postings is never one they are made from or shown for, so a closed posting never
    // belongs to a requisition that could still use it.
    @Test
    void closingAndSourceNeverOverlap() {
        for (RequisitionStatus status : JobPostingRequisitionSync.CLOSING_STATUSES) {
            assertThat(JobPostingService.SOURCE_STATUSES).as(status.name()).doesNotContain(status);
        }
    }
}
