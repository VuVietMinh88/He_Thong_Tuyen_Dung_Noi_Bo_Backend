package vn.ttcs.recruitment.jobposting;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

// Task 303: a new JobPostingStatus makes this switch fail to compile until someone decides whether a posting in that
// status may still be edited (JobPostingService.EDITABLE_STATUSES).
class JobPostingStatusTest {

    @ParameterizedTest
    @EnumSource(JobPostingStatus.class)
    void everyStatusIsClassifiedForEditing(JobPostingStatus status) {
        boolean editable = switch (status) {
            case DRAFT -> true;
        };

        assertThat(JobPostingService.EDITABLE_STATUSES.contains(status)).isEqualTo(editable);
    }
}
