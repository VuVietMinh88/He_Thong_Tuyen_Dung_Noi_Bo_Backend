package vn.ttcs.recruitment.jobposting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import vn.ttcs.recruitment.requisition.RequisitionStatus;
import vn.ttcs.recruitment.requisition.RequisitionStatusChange;

import static org.assertj.core.api.Assertions.assertThat;

// Task 292: a new RequisitionStatus makes the first switch fail to compile until someone decides whether reaching it
// closes the requisition's postings (JobPostingRequisitionSync.CLOSING_STATUSES), and a new JobPostingStatus the
// second until someone decides whether a posting in it is closed then (CLOSABLE_STATUSES). Task 355: a posting taken
// down is closed too. Task 356: an expired one too.
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
            case DRAFT, PENDING_APPROVAL, REVISION_REQUESTED, APPROVED, PUBLISHED, UNPUBLISHED, EXPIRED -> true;
            case CLOSED -> false;
        };

        assertThat(JobPostingRequisitionSync.CLOSABLE_STATUSES.contains(status)).isEqualTo(closable);
    }

    // Task 291: the changes that ask for confirmation while candidates are still in the pipeline are exactly those that
    // close the requisition's postings: both are the final closures (FILLED, CANCELLED).
    @ParameterizedTest
    @EnumSource(RequisitionStatusChange.class)
    void thePipelineIsCheckedExactlyForTheChangesThatClosePostings(RequisitionStatusChange change) {
        assertThat(change.checksActivePipeline())
                .isEqualTo(JobPostingRequisitionSync.CLOSING_STATUSES.contains(change.target()));
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
