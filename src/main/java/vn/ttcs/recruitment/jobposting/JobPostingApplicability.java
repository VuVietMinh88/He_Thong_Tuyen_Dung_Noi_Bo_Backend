package vn.ttcs.recruitment.jobposting;

import vn.ttcs.recruitment.requisition.RequisitionStatus;

import java.time.LocalDate;

/**
 * Task 320 (story S4-02): what a candidate who opens a posting by its link finds, decided in one place from the
 * posting's status, its requisition's status, its application deadline and today (the business date,
 * BusinessCalendar.today()). The public detail answers OPEN and EXPIRED with the posting (applicationOpen true or
 * false) and NOT_PUBLIC with 404, exactly like an id that does not exist.
 * <p>
 * The public list and its filter values keep their own SQL condition (JobPostingReadRepository.PUBLIC_WHERE), which
 * selects exactly the OPEN postings; JobPostingPortalRuleIntegrationTest checks that the two agree on every posting
 * status, a deadline before, on and after today, and an approved or a paused requisition (JobPostingApplicabilityTest
 * covers every requisition status for the Java rule).
 * <p>
 * Task 321: JobPostingApplicationGate applies the same rule when an application arrives (for the apply API, task 326),
 * on the rows it has locked and with today read after the locks: OPEN passes, EXPIRED is 409 JOB_POSTING_EXPIRED (the
 * closed notice), NOT_PUBLIC is 404. So the page and the server never disagree about a posting at one moment.
 */
enum JobPostingApplicability {
    // Listed, and candidates may still apply: the deadline day itself is open until 23:59:59 in the business zone.
    OPEN,
    // The detail still shows the posting, with a notice instead of the apply form; the list no longer has it.
    EXPIRED,
    // Drafts, postings under review or approved but not published, closed ones, and every posting whose requisition is
    // not in JobPostingService.SOURCE_STATUSES (a paused one, for example): 404.
    NOT_PUBLIC;

    static JobPostingApplicability of(JobPostingStatus status, RequisitionStatus requisition, LocalDate deadline,
                                      LocalDate today) {
        // A posting outside the review statuses always has a deadline (V22 job_posting_details_required); a missing
        // one is never public rather than a NullPointerException.
        if (deadline == null || !JobPostingService.SOURCE_STATUSES.contains(requisition)
                || !JobPostingService.PORTAL_STATUSES.contains(status)) {
            return NOT_PUBLIC;
        }
        return JobPostingService.PUBLIC_STATUSES.contains(status) && !deadlinePassed(deadline, today) ? OPEN : EXPIRED;
    }

    // Task 304/308 meaning of the deadline: the last day candidates may apply, inclusive. It has passed from 00:00 of
    // the next day in the business zone.
    static boolean deadlinePassed(LocalDate deadline, LocalDate today) {
        return deadline.isBefore(today);
    }
}
