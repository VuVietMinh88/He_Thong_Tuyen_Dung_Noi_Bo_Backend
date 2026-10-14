package vn.ttcs.recruitment.jobposting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import vn.ttcs.recruitment.requisition.RequisitionStatus;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.ttcs.recruitment.jobposting.JobPostingApplicability.EXPIRED;
import static vn.ttcs.recruitment.jobposting.JobPostingApplicability.NOT_PUBLIC;
import static vn.ttcs.recruitment.jobposting.JobPostingApplicability.OPEN;

// Task 320 (story S4-02): "Xây dựng API chi tiết tin tuyển dụng công khai". The one rule of what a candidate who opens
// a posting finds, for every posting status, every requisition status and a deadline before, on and after today (and
// none). The expected answer is written out below with its own exhaustive switches, not with JobPostingService's sets,
// so a new status of either enum does not compile here until someone decides what candidates see.
class JobPostingApplicabilityTest {
    // 30 Nov 2026: the deadline of the integration tests.
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 30);
    private static final List<LocalDate> DEADLINES = Arrays.asList(TODAY.minusDays(1), TODAY, TODAY.plusDays(1), null);

    @ParameterizedTest
    @EnumSource(JobPostingStatus.class)
    void everyPostingStatusRequisitionStatusAndDeadlineHasOneAnswer(JobPostingStatus status) {
        for (RequisitionStatus requisition : RequisitionStatus.values()) {
            for (LocalDate deadline : DEADLINES) {
                assertThat(JobPostingApplicability.of(status, requisition, deadline, TODAY))
                        .as("%s, requisition %s, deadline %s", status, requisition, deadline)
                        .isEqualTo(expected(status, requisition, deadline, TODAY));
            }
        }
    }

    // The deadline is the last day to apply: open on that day, expired from the next one. Only the date counts, so a
    // year's end and a leap day behave like any other day.
    @Test
    void theDeadlineDayItselfIsStillOpen() {
        assertThat(JobPostingApplicability.deadlinePassed(TODAY, TODAY)).isFalse();
        assertThat(JobPostingApplicability.deadlinePassed(TODAY.plusDays(1), TODAY)).isFalse();
        assertThat(JobPostingApplicability.deadlinePassed(TODAY.minusDays(1), TODAY)).isTrue();
        assertThat(JobPostingApplicability.deadlinePassed(LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 1)))
                .isTrue();
        assertThat(JobPostingApplicability.deadlinePassed(LocalDate.of(2028, 2, 29), LocalDate.of(2028, 2, 29)))
                .isFalse();

        assertThat(JobPostingApplicability.of(JobPostingStatus.PUBLISHED, RequisitionStatus.APPROVED, TODAY, TODAY))
                .isEqualTo(OPEN);
        assertThat(JobPostingApplicability.of(JobPostingStatus.PUBLISHED, RequisitionStatus.APPROVED, TODAY,
                TODAY.plusDays(1))).isEqualTo(EXPIRED);
        // A posting past its deadline whose requisition is paused shows nothing, not an expired notice.
        assertThat(JobPostingApplicability.of(JobPostingStatus.PUBLISHED, RequisitionStatus.PAUSED, TODAY,
                TODAY.plusDays(1))).isEqualTo(NOT_PUBLIC);
        // Task 356: the daily task changes nothing candidates see: expired by its date before, by its status after.
        assertThat(JobPostingApplicability.of(JobPostingStatus.EXPIRED, RequisitionStatus.APPROVED, TODAY,
                TODAY.plusDays(1))).isEqualTo(EXPIRED);
        assertThat(JobPostingApplicability.of(JobPostingStatus.EXPIRED, RequisitionStatus.PAUSED, TODAY,
                TODAY.plusDays(1))).isEqualTo(NOT_PUBLIC);
    }

    // The answer candidates should get. A posting is public only while its requisition is approved (task 290 hides
    // the postings of a paused one); a published posting is open on and before its deadline day and expired after it;
    // a posting the daily task expired (task 356) is expired whatever the date; no other status is ever public. A
    // missing deadline (only drafts and postings sent back have none) is never public.
    private static JobPostingApplicability expected(JobPostingStatus status, RequisitionStatus requisition,
                                                    LocalDate deadline, LocalDate today) {
        boolean requisitionApproved = switch (requisition) {
            case APPROVED -> true;
            case DRAFT, PENDING_APPROVAL, RETURNED, REJECTED, PAUSED, FILLED, CANCELLED -> false;
        };
        if (!requisitionApproved || deadline == null) {
            return NOT_PUBLIC;
        }
        return switch (status) {
            case PUBLISHED -> today.isAfter(deadline) ? EXPIRED : OPEN;
            // Task 356: the status EXPIRED (the case label) shows the closed notice (the answer EXPIRED).
            case EXPIRED -> EXPIRED;
            // Task 355: a posting taken down is never public again.
            case DRAFT, PENDING_APPROVAL, REVISION_REQUESTED, APPROVED, CLOSED, UNPUBLISHED -> NOT_PUBLIC;
        };
    }
}
