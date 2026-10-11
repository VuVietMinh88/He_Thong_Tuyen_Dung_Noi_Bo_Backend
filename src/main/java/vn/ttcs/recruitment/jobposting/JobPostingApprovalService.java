package vn.ttcs.recruitment.jobposting;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.common.BusinessCalendar;
import vn.ttcs.recruitment.jobposting.JobPostingAccess.Caller;
import vn.ttcs.recruitment.jobposting.JobPostingAccess.Writer;
import vn.ttcs.recruitment.jobposting.JobPostingSourceRepository.SourceRequisition;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Task 308 (story S3-10): HR's review of a posting sent for review (approve or ask for changes) and its publication.
 * Both need JOB_POSTINGS_WRITE_ALL (ADMIN, HR_MANAGER), in SecurityConfiguration and again here after every lock
 * (JobPostingAccess.requireReviewer). Nobody approves a posting they wrote, edited or last sent for review
 * (JobPostingReviewRule). Only an APPROVED posting is published, so every public posting was reviewed first; the
 * posting's content is frozen from the submission on and every step carries the revision the caller looked at, so
 * what is published is what was last approved. Every step adds an event to job_posting_events (V22) with its actor,
 * time and comment, in the same transaction as the status change.
 * <p>
 * Lock order like every posting write (JobPostingService): the actor's account, the actor's session, the requisition
 * row, then the posting row; the caller is checked again after each lock. The catalog values are not checked again:
 * one HR stopped using after the submission neither blocks the review nor the publication (BA/PO question 16).
 * The publication trail on the posting itself (who published it and when) is task 310; the PUBLISHED event already
 * records both.
 */
@Service
public class JobPostingApprovalService {
    // Task 308: the statuses a posting may be published from. JobPostingStatus asks every new status to be classified
    // here; JobPostingStatusTest does not compile until it is.
    static final Set<JobPostingStatus> PUBLISHABLE_STATUSES = EnumSet.of(JobPostingStatus.APPROVED);

    private static final String COMMENT_REQUIRED = "Vui lòng nhập nhận xét khi yêu cầu chỉnh sửa tin tuyển dụng.";

    private final JobPostingAccess access;
    private final JobPostingRepository postings;
    private final JobPostingSourceRepository sources;
    private final JobPostingReadRepository reads;
    private final JobPostingAuthorRepository authors;
    private final JobPostingEventRepository events;
    private final BusinessCalendar calendar;

    public JobPostingApprovalService(JobPostingAccess access, JobPostingRepository postings,
                                     JobPostingSourceRepository sources, JobPostingReadRepository reads,
                                     JobPostingAuthorRepository authors, JobPostingEventRepository events,
                                     BusinessCalendar calendar) {
        this.access = access;
        this.postings = postings;
        this.sources = sources;
        this.reads = reads;
        this.authors = authors;
        this.events = events;
        this.calendar = calendar;
    }

    /**
     * HR approves a posting sent for review, or asks its authors for changes (from PENDING_APPROVAL, or from APPROVED
     * while it is not published yet). Checks in order: the body (400), a comment when asking for changes (400, before
     * any lock), JOB_POSTINGS_WRITE_ALL and the read permission (401/403, before any lookup), the posting exists (404),
     * it is in the caller's read scope (403), the decision may be taken in its status (409), the caller saw its
     * latest revision (409), for APPROVE the caller did not write, edit or last submit it (403), its requisition is
     * still approved (409), and for APPROVE the application deadline has not passed (409: HR cannot change the
     * deadline of a frozen posting and asks for changes instead). The two 409s come before the 403 on purpose, like
     * the requisition approval: someone who lost a race to another reviewer gets a message to reload.
     */
    @Transactional
    public JobPostingView decide(Jwt jwt, UUID id, JobPostingDecisionRequest request) {
        JobPostingDecision decision = request.decision();
        if (decision.requiresComment() && request.comment() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "JOB_POSTING_COMMENT_REQUIRED", COMMENT_REQUIRED,
                    Map.of("comment", COMMENT_REQUIRED));
        }
        Writer writer = access.lockWriter(jwt);
        access.requireReviewer(jwt, writer);
        UUID requisitionId = postings.findRequisitionIdById(id).orElseThrow(JobPostingService::notFound);
        SourceRequisition source = sources.lockSource(requisitionId).orElseThrow(JobPostingService::notFound);
        access.requireReviewer(jwt, writer);
        var locked = postings.findByIdForUpdate(id);
        Caller caller = access.requireReviewer(jwt, writer);
        JobPosting posting = locked.orElseThrow(JobPostingService::notFound);
        access.requireInScope(caller, requisitionId);
        if (!decision.from().contains(posting.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "JOB_POSTING_NOT_UNDER_REVIEW",
                    "Tin tuyển dụng không còn ở bước duyệt này. Vui lòng tải lại để xem trạng thái mới nhất.");
        }
        JobPostingService.requireRevision(posting, request.expectedRevision());
        if (decision == JobPostingDecision.APPROVE) {
            // Read under the posting lock: a save (which adds an author) and a submission (which changes the latest
            // submitter) both hold it, so the answer cannot change before this decision commits.
            Set<UUID> postingAuthors = authors.findAuthors(id);
            UUID latestSubmitter = reads.findRow(id).orElseThrow().latestSubmitterId();
            if (!JobPostingReviewRule.mayApprove(caller.id(), postingAuthors, latestSubmitter)) {
                throw new AccessDeniedException("The caller wrote, edited or last submitted this job posting");
            }
        }
        JobPostingService.requireSourceApproved(source);
        if (decision == JobPostingDecision.APPROVE) {
            requireDeadlineNotPassed(posting, calendar.today());
        }
        JobPostingStatus before = posting.getStatus();
        posting.moveTo(before, decision.to());
        postings.flush();
        events.append(id, posting.getRevision(), decision.eventType(), before, decision.to(), caller.id(),
                access.now(), request.comment());
        return view(id, caller);
    }

    /**
     * HR publishes an approved posting. Anyone with JOB_POSTINGS_WRITE_ALL may, the approver included. Checks in
     * order: JOB_POSTINGS_WRITE_ALL and the read permission (401/403, before any lookup), the posting exists (404), it
     * is in the caller's read scope (403), it is not published yet (409 JOB_POSTING_ALREADY_PUBLISHED) and it is
     * approved (409 JOB_POSTING_NOT_APPROVED), the caller saw its latest revision (409), its requisition is still
     * approved (409), the application deadline has not passed (409). The PUBLISHED event records the caller from the
     * token and the server time, never anything from the body. Candidates see the posting from task 309 on.
     */
    @Transactional
    public JobPostingView publish(Jwt jwt, UUID id, JobPostingPublishRequest request) {
        Writer writer = access.lockWriter(jwt);
        access.requireReviewer(jwt, writer);
        UUID requisitionId = postings.findRequisitionIdById(id).orElseThrow(JobPostingService::notFound);
        SourceRequisition source = sources.lockSource(requisitionId).orElseThrow(JobPostingService::notFound);
        access.requireReviewer(jwt, writer);
        var locked = postings.findByIdForUpdate(id);
        Caller caller = access.requireReviewer(jwt, writer);
        JobPosting posting = locked.orElseThrow(JobPostingService::notFound);
        access.requireInScope(caller, requisitionId);
        if (posting.getStatus() == JobPostingStatus.PUBLISHED) {
            throw new ApiException(HttpStatus.CONFLICT, "JOB_POSTING_ALREADY_PUBLISHED",
                    "Tin tuyển dụng đã được xuất bản.");
        }
        if (!PUBLISHABLE_STATUSES.contains(posting.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "JOB_POSTING_NOT_APPROVED",
                    "Tin tuyển dụng chưa được duyệt nên chưa thể xuất bản.");
        }
        JobPostingService.requireRevision(posting, request.expectedRevision());
        JobPostingService.requireSourceApproved(source);
        requireDeadlineNotPassed(posting, calendar.today());
        posting.moveTo(JobPostingStatus.APPROVED, JobPostingStatus.PUBLISHED);
        postings.flush();
        events.append(id, posting.getRevision(), JobPostingEventType.PUBLISHED, JobPostingStatus.APPROVED,
                JobPostingStatus.PUBLISHED, writer.actorId(), access.now(), null);
        return view(id, caller);
    }

    // A posting whose last day to apply has passed is neither approved nor published: candidates could not apply.
    // Today is the business date (Vietnam by default), read after the posting lock; the deadline day itself is still
    // allowed. V22 makes every posting under review or approved have a deadline.
    private static void requireDeadlineNotPassed(JobPosting posting, LocalDate today) {
        LocalDate deadline = posting.getApplicationDeadline();
        if (deadline != null && deadline.isBefore(today)) {
            throw new ApiException(HttpStatus.CONFLICT, "JOB_POSTING_DEADLINE_PASSED",
                    "Hạn nộp hồ sơ của tin đã qua. Vui lòng yêu cầu chỉnh sửa để đổi hạn nộp trước khi duyệt "
                            + "hoặc xuất bản.");
        }
    }

    // The answer of a step: the row as it is now saved, read in the same transaction.
    private JobPostingView view(UUID id, Caller caller) {
        return JobPostingView.from(reads.findRow(id).orElseThrow(), caller, authors.findAuthors(id));
    }
}
