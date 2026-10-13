package vn.ttcs.recruitment.jobposting;

import java.util.EnumSet;
import java.util.Set;

/**
 * Task 308: what HR decides about a posting sent for review (POST /job-postings/{id}/approvals). CODES is the pattern
 * of JobPostingDecisionRequest.action: a code that is not listed here is a form error, never a 500. There is no
 * rejection: HR asks for changes instead.
 */
public enum JobPostingDecision {
    // The posting may be published. A comment is optional.
    APPROVE(EnumSet.of(JobPostingStatus.PENDING_APPROVAL), JobPostingStatus.APPROVED, JobPostingEventType.APPROVED,
            false),
    // The posting goes back to its authors. Also possible after the approval, as long as it is not published yet, so
    // HR can still have an approved text changed. A comment (what to change) is required.
    REQUEST_REVISION(EnumSet.of(JobPostingStatus.PENDING_APPROVAL, JobPostingStatus.APPROVED),
            JobPostingStatus.REVISION_REQUESTED, JobPostingEventType.REVISION_REQUESTED, true);

    public static final String CODES = "APPROVE|REQUEST_REVISION";
    public static final String CODES_MESSAGE =
            "Thao tác duyệt chỉ được là APPROVE (duyệt) hoặc REQUEST_REVISION (yêu cầu chỉnh sửa).";

    private final Set<JobPostingStatus> from;
    private final JobPostingStatus to;
    private final JobPostingEventType eventType;
    private final boolean commentRequired;

    JobPostingDecision(Set<JobPostingStatus> from, JobPostingStatus to, JobPostingEventType eventType,
                       boolean commentRequired) {
        this.from = from;
        this.to = to;
        this.eventType = eventType;
        this.commentRequired = commentRequired;
    }

    // The statuses this decision may be taken in. JobPostingStatus asks every new status to be classified here;
    // JobPostingDecisionTest does not compile until it is.
    public Set<JobPostingStatus> from() {
        return EnumSet.copyOf(from);
    }

    public JobPostingStatus to() {
        return to;
    }

    public JobPostingEventType eventType() {
        return eventType;
    }

    public boolean requiresComment() {
        return commentRequired;
    }
}
