package vn.ttcs.recruitment.jobposting;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.ttcs.recruitment.requisition.RequisitionStatus;
import vn.ttcs.recruitment.requisition.RequisitionStatusListener;
import vn.ttcs.recruitment.requisition.RequisitionStatusTransition;

import java.util.EnumSet;
import java.util.Set;

/**
 * Task 292 (story S3-07): "Tự gỡ tin tuyển dụng khi đóng yêu cầu". Closing a requisition as filled or cancelling it
 * (task 290) closes its job postings for good, in the status change's transaction, after the requisition row is
 * locked, moved and recorded: lock order account → session → requisition → posting, the order of every posting
 * writer, so nothing deadlocks and nothing waits after the posting lock (the event insert only takes FOR KEY SHARE).
 * Postings are read after the requisition lock (READ COMMITTED), so one created or published just before is seen and
 * closed. A failure here rolls the requisition change back too. No caller check: the requisition service authorized
 * the closer, who needs no JOB_POSTINGS permission. Pausing and resuming change no posting: the portal shows a posting
 * only while its requisition is in JobPostingService.SOURCE_STATUSES (task 309).
 */
@Component
public class JobPostingRequisitionSync implements RequisitionStatusListener {
    // RequisitionStatus asks every new status to be classified here; JobPostingClosingStatusTest does not compile until
    // it is.
    static final Set<RequisitionStatus> CLOSING_STATUSES =
            EnumSet.of(RequisitionStatus.FILLED, RequisitionStatus.CANCELLED);

    // JobPostingStatus asks every new status to be classified here; JobPostingClosingStatusTest does not compile until
    // it is. Task 355: a posting taken down is closed too, so it never belongs to a closed requisition.
    static final Set<JobPostingStatus> CLOSABLE_STATUSES = EnumSet.of(JobPostingStatus.DRAFT,
            JobPostingStatus.PENDING_APPROVAL, JobPostingStatus.REVISION_REQUESTED, JobPostingStatus.APPROVED,
            JobPostingStatus.PUBLISHED, JobPostingStatus.UNPUBLISHED);

    private final JobPostingRepository postings;
    private final JobPostingEventRepository events;

    public JobPostingRequisitionSync(JobPostingRepository postings, JobPostingEventRepository events) {
        this.postings = postings;
        this.events = events;
    }

    // Never a transaction of its own: the closure commits or rolls back with the requisition change. Exceptions
    // propagate; nothing is caught and skipped.
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void afterChange(RequisitionStatusTransition transition) {
        if (!CLOSING_STATUSES.contains(transition.after())) {
            return;
        }
        for (JobPosting posting : postings.findByRequisitionIdForUpdate(transition.requisitionId())) {
            JobPostingStatus before = posting.getStatus();
            if (!CLOSABLE_STATUSES.contains(before)) {
                // Already CLOSED.
                continue;
            }
            // A new revision; the publication trail and updatedAt (the last content edit) are kept.
            posting.moveTo(before, JobPostingStatus.CLOSED);
            postings.flush();
            events.append(posting.getId(), posting.getRevision(), JobPostingEventType.CLOSED, before,
                    JobPostingStatus.CLOSED, transition.actorId(), transition.at(), transition.reason());
        }
    }
}
