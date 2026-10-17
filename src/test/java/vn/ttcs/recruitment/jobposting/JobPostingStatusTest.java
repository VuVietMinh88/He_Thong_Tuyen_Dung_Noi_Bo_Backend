package vn.ttcs.recruitment.jobposting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

// Task 303: a new JobPostingStatus makes these switches fail to compile until someone decides whether a posting in that
// status may still be edited (JobPostingService.EDITABLE_STATUSES). Task 308: and whether it may be sent for review
// (JobPostingService.SUBMITTABLE_STATUSES) and published (JobPostingApprovalService.PUBLISHABLE_STATUSES). Task 309:
// and whether candidates see it on the public portal (JobPostingService.PUBLIC_STATUSES). Task 292: a posting closed
// with its requisition is none of these. Task 320: and whether candidates may open its detail page
// (JobPostingService.PORTAL_STATUSES). Task 355: and whether it may be taken down
// (JobPostingService.UNPUBLISHABLE_STATUSES); a posting taken down is none of the others. Task 356: an expired posting
// still opens its detail (with the closed notice) and may be taken down, and is none of the others.
class JobPostingStatusTest {

    @ParameterizedTest
    @EnumSource(JobPostingStatus.class)
    void everyStatusIsClassifiedForEditing(JobPostingStatus status) {
        boolean editable = switch (status) {
            case DRAFT, REVISION_REQUESTED -> true;
            case PENDING_APPROVAL, APPROVED, PUBLISHED, CLOSED, UNPUBLISHED, EXPIRED -> false;
        };

        assertThat(JobPostingService.EDITABLE_STATUSES.contains(status)).isEqualTo(editable);
    }

    @ParameterizedTest
    @EnumSource(JobPostingStatus.class)
    void everyStatusIsClassifiedForSubmitting(JobPostingStatus status) {
        boolean submittable = switch (status) {
            case DRAFT, REVISION_REQUESTED -> true;
            case PENDING_APPROVAL, APPROVED, PUBLISHED, CLOSED, UNPUBLISHED, EXPIRED -> false;
        };

        assertThat(JobPostingService.SUBMITTABLE_STATUSES.contains(status)).isEqualTo(submittable);
    }

    @ParameterizedTest
    @EnumSource(JobPostingStatus.class)
    void everyStatusIsClassifiedForPublishing(JobPostingStatus status) {
        boolean publishable = switch (status) {
            case APPROVED -> true;
            case DRAFT, PENDING_APPROVAL, REVISION_REQUESTED, PUBLISHED, CLOSED, UNPUBLISHED, EXPIRED -> false;
        };

        assertThat(JobPostingApprovalService.PUBLISHABLE_STATUSES.contains(status)).isEqualTo(publishable);
    }

    @ParameterizedTest
    @EnumSource(JobPostingStatus.class)
    void everyStatusIsClassifiedForThePublicPortal(JobPostingStatus status) {
        boolean shown = switch (status) {
            case PUBLISHED -> true;
            case DRAFT, PENDING_APPROVAL, REVISION_REQUESTED, APPROVED, CLOSED, UNPUBLISHED, EXPIRED -> false;
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
            // Task 356: the daily task changes nothing candidates see; an expired posting keeps its notice.
            case PUBLISHED, EXPIRED -> true;
            case DRAFT, PENDING_APPROVAL, REVISION_REQUESTED, APPROVED, CLOSED, UNPUBLISHED -> false;
        };

        assertThat(JobPostingService.PORTAL_STATUSES.contains(status)).isEqualTo(opens);
    }

    // Task 355 (story S4-08): only a published posting is taken down; one taken down already, a closed one and one
    // that was never published are not. Task 356: an expired posting is taken down too.
    @ParameterizedTest
    @EnumSource(JobPostingStatus.class)
    void everyStatusIsClassifiedForUnpublishing(JobPostingStatus status) {
        boolean unpublishable = switch (status) {
            case PUBLISHED, EXPIRED -> true;
            case DRAFT, PENDING_APPROVAL, REVISION_REQUESTED, APPROVED, CLOSED, UNPUBLISHED -> false;
        };

        assertThat(JobPostingService.UNPUBLISHABLE_STATUSES.contains(status)).isEqualTo(unpublishable);
    }

    // Task 320: a posting candidates may apply to is always one whose detail opens.
    @Test
    void theDetailOpensForEveryPublicStatus() {
        assertThat(JobPostingService.PORTAL_STATUSES).containsAll(JobPostingService.PUBLIC_STATUSES);
    }

    // Task 356: the deadline is the last day to apply, inclusive; the daily task expires a posting from the next day
    // on, and catches up a missed day.
    @Test
    void theDeadlineHasPassedOnlyAfterItsDay() {
        LocalDate deadline = LocalDate.of(2026, 11, 30);

        assertThat(JobPostingApplicability.deadlinePassed(deadline, LocalDate.of(2026, 11, 29))).isFalse();
        assertThat(JobPostingApplicability.deadlinePassed(deadline, deadline)).isFalse();
        assertThat(JobPostingApplicability.deadlinePassed(deadline, LocalDate.of(2026, 12, 1))).isTrue();
        assertThat(JobPostingApplicability.deadlinePassed(deadline, LocalDate.of(2026, 12, 4))).isTrue();
        assertThat(JobPostingApplicability.deadlinePassed(LocalDate.of(2028, 2, 29), LocalDate.of(2028, 3, 1)))
                .isTrue();
    }
}
