package vn.ttcs.recruitment.jobposting;

import java.util.Set;
import java.util.UUID;

/**
 * Task 308: who may approve a job posting. The single rule of the decision (JobPostingApprovalService.decide) and of
 * actions.canApprove (JobPostingView), so the button and the server never disagree.
 * <p>
 * Nobody approves text they wrote or sent: not an author of the posting (its creator and every account that ever saved
 * its content, job_posting_authors, task 303) and not the account that sent it for review last. This is stricter than
 * the requisition rule (ApproverRule), which does not exclude editors: HR_MANAGER and ADMIN may edit every posting and
 * are also its reviewers, so without the author set HR could rewrite a posting HR sent back, let the recruiter send it
 * again and then approve its own text. An account that only asked for a revision, or an earlier submitter that never
 * wrote the content, may approve. Publishing has no such rule.
 */
public final class JobPostingReviewRule {
    private JobPostingReviewRule() {
    }

    // authors always holds the creator (added when the posting is created); latestSubmitter is null before the first
    // submission.
    public static boolean mayApprove(UUID caller, Set<UUID> authors, UUID latestSubmitter) {
        return !authors.contains(caller) && !caller.equals(latestSubmitter);
    }
}
