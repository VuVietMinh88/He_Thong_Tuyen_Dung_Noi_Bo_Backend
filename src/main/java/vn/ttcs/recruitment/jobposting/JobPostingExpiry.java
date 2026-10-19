package vn.ttcs.recruitment.jobposting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.common.BusinessCalendar;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Task 356 (story S4-08): "Tạo tác vụ hàng ngày tự đánh dấu tin hết hạn". Every day at 00:05 in the business time zone
 * (app.job-posting-expiry.cron in app.business-zone, JobPostingExpirySchedule) each PUBLISHED posting whose application
 * deadline has passed (JobPostingApplicability.deadlinePassed) moves to EXPIRED with a new revision, and an EXPIRED
 * step without an actor (no person does it) records when. Who published it and when, and updatedAt (the last content
 * edit), are kept. Candidates see nothing change: the list hides such a posting by its date from 00:00 (task 309) and
 * its detail shows the closed notice by its date too (task 320); the status only follows the date. A day the task did
 * not run is caught up by the next run, which finds every posting still due. The requisition's status is not checked:
 * the posting of a paused requisition expires too.
 * <p>
 * Each posting is expired in its own READ COMMITTED transaction, in the lock order of every posting writer without an
 * account or session (none acts): its requisition (JobPostingSourceRepository.lockSource), then the posting
 * (JobPostingRepository.findByIdForUpdate). Today is read after both locks and the posting checked again, so a
 * take-down (task 355), a closure (task 292) or another run that committed while this one waited is seen and the
 * posting skipped. So a second run, a run started twice at the same time or on two servers never expires a posting
 * twice or changes one that is no longer PUBLISHED; V27 job_posting_events_one_expiry_idx is the last line. A posting
 * that fails is logged and left for the next run; the others still expire.
 */
@Component
public class JobPostingExpiry {
    private static final Logger LOG = LoggerFactory.getLogger(JobPostingExpiry.class);

    private final JobPostingRepository postings;
    private final JobPostingSourceRepository sources;
    private final JobPostingEventRepository events;
    private final JobPostingAccess access;
    private final BusinessCalendar calendar;
    private final TransactionTemplate transactions;

    public JobPostingExpiry(JobPostingRepository postings, JobPostingSourceRepository sources,
                            JobPostingEventRepository events, JobPostingAccess access, BusinessCalendar calendar,
                            PlatformTransactionManager transactionManager) {
        this.postings = postings;
        this.sources = sources;
        this.events = events;
        this.access = access;
        this.calendar = calendar;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    // The daily run, which JobPostingExpirySchedule registers. "-" as the cron turns it off (the tests do,
    // application-default.properties under src/test).
    public void runDaily() {
        int expired = expireDue();
        LOG.info("Job posting expiry: {} expired", expired);
    }

    /**
     * Expires every PUBLISHED posting whose deadline is before today (the business date when the run starts) and
     * returns how many it moved. Not transactional itself: one transaction per posting, so the locks of one posting are
     * held only while it is expired. A posting found in the list but changed meanwhile is skipped, not counted.
     */
    public int expireDue() {
        int expired = 0;
        for (UUID id : postings.findIdsToExpire(JobPostingStatus.PUBLISHED, calendar.today())) {
            try {
                if (expire(id)) {
                    expired++;
                }
            } catch (RuntimeException exception) {
                LOG.error("Job posting {} was not expired; the next run retries it", id, exception);
            }
        }
        return expired;
    }

    /**
     * Expires one posting if it is still PUBLISHED and its deadline has passed, in its own transaction; true when it
     * moved. Today is read after the locks, so a run that waited for a lock past midnight decides on the new date.
     */
    public boolean expire(UUID id) {
        return Boolean.TRUE.equals(transactions.execute(transaction -> {
            // The requisition of a posting never changes, so it is read before any lock, and locked first.
            UUID requisitionId = postings.findRequisitionIdById(id).orElse(null);
            if (requisitionId == null) {
                return false;
            }
            sources.lockSource(requisitionId);
            JobPosting posting = postings.findByIdForUpdate(id).orElse(null);
            LocalDate today = calendar.today(); // after the locks
            if (posting == null || posting.getStatus() != JobPostingStatus.PUBLISHED
                    || !JobPostingApplicability.deadlinePassed(posting.getApplicationDeadline(), today)) {
                return false; // taken down, closed or expired meanwhile, or not due yet
            }
            posting.moveTo(JobPostingStatus.PUBLISHED, JobPostingStatus.EXPIRED);
            postings.flush();
            events.append(id, posting.getRevision(), JobPostingEventType.EXPIRED, JobPostingStatus.PUBLISHED,
                    JobPostingStatus.EXPIRED, null, access.now(), null);
            return true;
        }));
    }
}
