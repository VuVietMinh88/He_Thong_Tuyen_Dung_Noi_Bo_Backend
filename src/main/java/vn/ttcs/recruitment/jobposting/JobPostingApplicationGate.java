package vn.ttcs.recruitment.jobposting;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.common.BusinessCalendar;
import vn.ttcs.recruitment.requisition.RequisitionStatus;

import java.sql.Connection;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Task 321 (story S4-02): may a candidate still apply to this posting? The server-side check the apply API (task 326,
 * story S4-03) makes before it writes anything. The public detail (task 320) answers the same question for the page
 * with the same rule (JobPostingApplicability), but only as it stood when the page was read; this check is made again
 * when the application arrives, so a posting that expired, was closed or paused after the page was opened is refused.
 * <p>
 * Contract for the apply API (task 326): call requireOpen first, in your own READ COMMITTED read-write transaction
 * (the default of @Transactional), before writing anything. It locks the requisition FOR NO KEY UPDATE (the
 * RequisitionPipelineSource contract: lock the requisition first, refuse FILLED and CANCELLED), then the posting FOR
 * SHARE, then reads today, so a closure, pause or other posting write committed while it waited is seen, and none
 * can commit before your application does. Applications to one requisition therefore run one after another, queued
 * with those writes in arrival order: a write that waits for an application in progress goes before every
 * application that arrives after it (a share lock on the requisition would let those through first, and a steady
 * stream of them could keep the write waiting indefinitely). Never UPDATE the requisition or the posting afterwards
 * in the same transaction: only their own modules write them, with their own checks, revision and events, which an
 * UPDATE here would bypass. Lock order: your own account and session, if any, then this gate, then your own rows. Do
 * slow work (captcha, rate limit, storing and parsing the CV) before the call and send mail after the commit, so the
 * locks are held briefly. No caller check: candidates are anonymous.
 * <p>
 * requisitionId is internal (task 316): use it only for your own rows and the RequisitionPipelineSource count; never
 * return it in a candidate-facing response, lookup result or email.
 */
@Component
public class JobPostingApplicationGate {
    /**
     * The posting that takes the application: its id, its requisition (internal, see the class comment) and its
     * application deadline, read under the locks.
     */
    public record OpenJobPosting(UUID postingId, UUID requisitionId, LocalDate applicationDeadline) { }

    private final JobPostingRepository postings;
    private final JobPostingSourceRepository sources;
    private final JobPostingReadRepository reads;
    private final BusinessCalendar calendar;

    public JobPostingApplicationGate(JobPostingRepository postings, JobPostingSourceRepository sources,
                                     JobPostingReadRepository reads, BusinessCalendar calendar) {
        this.postings = postings;
        this.sources = sources;
        this.reads = reads;
        this.calendar = calendar;
    }

    /**
     * Returns the posting when candidates may still apply to it (JobPostingApplicability.OPEN) and keeps it so until
     * the caller's transaction ends. Otherwise throws, in the words of the public detail:
     * <ul>
     *   <li>409 JOB_POSTING_EXPIRED when its detail shows the closed notice (published, requisition still approved,
     *   deadline passed in the business zone; task 356: or expired by the daily task): the page that was opened before
     *   00:00 shows the notice;</li>
     *   <li>404 JOB_POSTING_NOT_FOUND, the body of an unknown id, when the posting is not public (not published,
     *   closed, or its requisition is no longer approved, for example paused) or does not exist.</li>
     * </ul>
     * Writes nothing. Throws IllegalTransactionStateException without a transaction and IllegalStateException in a
     * read-only transaction or one with another isolation than READ COMMITTED.
     * <p>
     * A refusal (the ApiException above) does not mark the caller's transaction rollback-only (noRollbackFor): let it
     * leave your @Transactional method and the transaction rolls back as usual, or catch it (for example to record the
     * refused attempt) and commit; the locks already taken last until then. Any other exception (a misuse above,
     * a failed statement) still marks the transaction rollback-only, and committing it then throws
     * UnexpectedRollbackException.
     */
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = ApiException.class)
    public OpenJobPosting requireOpen(UUID postingId) {
        return requireOpen(postingId, () -> { });
    }

    // Authenticated referrals pass a check of their already-locked account/session and current permissions. Each
    // lock can wait, so refresh the caller before interpreting the row or allowing storage to continue.
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = ApiException.class)
    public OpenJobPosting requireOpen(UUID postingId, Runnable callerCheck) {
        requireLockingTransaction();
        // The requisition of a posting never changes, so it is read before any lock, and locked first.
        UUID requisitionId = postings.findRequisitionIdById(postingId).orElseThrow(JobPostingService::notFound);
        // No API deletes a requisition (V21 RESTRICT), so the posting's requisition is always there.
        var lockedRequisition = sources.lockStatusForApplication(requisitionId);
        callerCheck.run();
        RequisitionStatus requisition = lockedRequisition.orElseThrow(JobPostingService::notFound);
        var lockedPosting = reads.lockForApplication(postingId);
        callerCheck.run();
        var posting = lockedPosting.orElseThrow(JobPostingService::notFound);
        // After both locks: a wait past 00:00 in the business zone sees the new date.
        LocalDate today = calendar.today();
        return switch (JobPostingApplicability.of(posting.status(), requisition, posting.applicationDeadline(),
                today)) {
            case OPEN -> new OpenJobPosting(postingId, requisitionId, posting.applicationDeadline());
            case EXPIRED -> throw expired();
            case NOT_PUBLIC -> throw JobPostingService.notFound();
        };
    }

    // Shared by the intake's early check and storage's final deadline checks after waits on their own rows.
    public static ApiException expired() {
        return new ApiException(HttpStatus.CONFLICT, "JOB_POSTING_EXPIRED",
                "Tin tuyển dụng đã hết hạn nộp hồ sơ.");
    }

    // A programming error of the caller, refused before any SQL. In REPEATABLE READ or SERIALIZABLE, a row changed
    // after the transaction's snapshot cannot be locked (PostgreSQL raises a serialization failure instead of reading
    // the new version), and PostgreSQL refuses row locks (FOR NO KEY UPDATE, FOR SHARE) in a read-only transaction.
    // null means the default isolation, READ COMMITTED in PostgreSQL.
    private static void requireLockingTransaction() {
        Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || (isolation != null && isolation != Connection.TRANSACTION_READ_COMMITTED)) {
            throw new IllegalStateException("JobPostingApplicationGate.requireOpen needs a read-write READ COMMITTED "
                    + "transaction");
        }
    }
}
