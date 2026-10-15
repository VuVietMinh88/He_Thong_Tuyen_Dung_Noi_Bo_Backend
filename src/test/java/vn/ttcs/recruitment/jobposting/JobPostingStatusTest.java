package vn.ttcs.recruitment.jobposting;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

// Task 303: a new JobPostingStatus makes these switches fail to compile until someone decides whether a posting in that
// status may still be edited (JobPostingService.EDITABLE_STATUSES). Task 308: and whether it may be sent for review
// (JobPostingService.SUBMITTABLE_STATUSES) and published (JobPostingApprovalService.PUBLISHABLE_STATUSES). Task 309:
// and whether candidates see it on the public portal (JobPostingService.PUBLIC_STATUSES). Task 292: a posting closed
// with its requisition is none of these.
class JobPostingStatusTest {

    @ParameterizedTest
    @EnumSource(JobPostingStatus.class)
    void everyStatusIsClassifiedForEditing(JobPostingStatus status) {
        boolean editable = switch (status) {
            case DRAFT, REVISION_REQUESTED -> true;
            case PENDING_APPROVAL, APPROVED, PUBLISHED, CLOSED -> false;
        };

        assertThat(JobPostingService.EDITABLE_STATUSES.contains(status)).isEqualTo(editable);
    }

    @ParameterizedTest
    @EnumSource(JobPostingStatus.class)
    void everyStatusIsClassifiedForSubmitting(JobPostingStatus status) {
        boolean submittable = switch (status) {
            case DRAFT, REVISION_REQUESTED -> true;
            case PENDING_APPROVAL, APPROVED, PUBLISHED, CLOSED -> false;
        };

        assertThat(JobPostingService.SUBMITTABLE_STATUSES.contains(status)).isEqualTo(submittable);
    }

    @ParameterizedTest
    @EnumSource(JobPostingStatus.class)
    void everyStatusIsClassifiedForPublishing(JobPostingStatus status) {
        boolean publishable = switch (status) {
            case APPROVED -> true;
            case DRAFT, PENDING_APPROVAL, REVISION_REQUESTED, PUBLISHED, CLOSED -> false;
        };

        assertThat(JobPostingApprovalService.PUBLISHABLE_STATUSES.contains(status)).isEqualTo(publishable);
    }

    @ParameterizedTest
    @EnumSource(JobPostingStatus.class)
    void everyStatusIsClassifiedForThePublicPortal(JobPostingStatus status) {
        boolean shown = switch (status) {
            case PUBLISHED -> true;
            case DRAFT, PENDING_APPROVAL, REVISION_REQUESTED, APPROVED, CLOSED -> false;
        };

        assertThat(JobPostingService.PUBLIC_STATUSES.contains(status)).isEqualTo(shown);
        assertThat(JobPostingService.PUBLIC_STATUS_NAMES.contains(status.name())).isEqualTo(shown);
    }
}
