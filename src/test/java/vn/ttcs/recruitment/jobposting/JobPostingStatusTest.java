package vn.ttcs.recruitment.jobposting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

// Task 303: a new JobPostingStatus makes these switches fail to compile until someone decides whether a posting in that
// status may still be edited (JobPostingService.EDITABLE_STATUSES). Task 308: and whether it may be sent for review
// (JobPostingService.SUBMITTABLE_STATUSES) and published (JobPostingApprovalService.PUBLISHABLE_STATUSES). Task 309:
// and whether candidates see it on the public portal (JobPostingService.PUBLIC_STATUSES). Task 292: a posting closed
// with its requisition is none of these. Task 320: and whether candidates may open its detail page
// (JobPostingService.PORTAL_STATUSES).
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

    // Task 320 (story S4-02): the statuses whose public detail still opens by its link (with a closed notice once the
    // posting is no longer open to applications).
    @ParameterizedTest
    @EnumSource(JobPostingStatus.class)
    void everyStatusIsClassifiedForThePortalDetail(JobPostingStatus status) {
        boolean opens = switch (status) {
            case PUBLISHED -> true;
            case DRAFT, PENDING_APPROVAL, REVISION_REQUESTED, APPROVED, CLOSED -> false;
        };

        assertThat(JobPostingService.PORTAL_STATUSES.contains(status)).isEqualTo(opens);
    }

    // Task 320: a posting candidates may apply to is always one whose detail opens.
    @Test
    void theDetailOpensForEveryPublicStatus() {
        assertThat(JobPostingService.PORTAL_STATUSES).containsAll(JobPostingService.PUBLIC_STATUSES);
    }
}
