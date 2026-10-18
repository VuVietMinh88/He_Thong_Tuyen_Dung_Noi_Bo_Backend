package vn.ttcs.recruitment.application;

import jakarta.validation.Validator;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.ttcs.recruitment.common.ApiException;

import java.sql.Connection;
import java.util.UUID;

/**
 * Task 350 (story S4-07, "Hồ sơ được gắn nhãn nguồn Giới thiệu nội bộ và tên người giới thiệu"): the storage boundary
 * of a referral, the 325/326 split of the referral API (task 351). An application a staff member submits for a candidate is stored
 * with who referred and why, in the caller's transaction (MANDATORY), so the application, its CV, its candidate link
 * (task 360), the referral row (V36) and, in task 351, the candidate's confirmation commit together or not at all.
 * <p>
 * TRAN DUC LUONG's ApplicationStorageService.store does the posting checks (an APPROVED requisition, a PUBLISHED posting
 * before its deadline), the shared duplicate rule of task 327 (a referral and a public application of one normalized
 * email in one requisition are duplicates of each other, in either order: 409), the candidate link and the CV. This
 * class adds three things around it:
 * <ol>
 * <li>The note is checked before any SQL: Windows line breaks become \n, a note without a visible character counts as
 * none, it is required, at most 2000 characters and plain text (@PlainText multiline): 400 VALIDATION_ERROR with
 * fieldErrors referralNote.</li>
 * <li>The requisition is locked FOR NO KEY UPDATE before store(), whoever calls this: the house rule of task 321 for
 * every write that makes an application active (RequisitionPipelineSource), so a closure or a pause of the requisition
 * waits for the referral and then counts it, and never the other way round with a stale count. store()'s own FOR SHARE
 * on the requisition is then a no-op (a transaction that holds a stronger row lock gets a weaker one for free). When
 * store() is wired to the portal's JobPostingApplicationGate (task 321), this lock is replaced by that call.</li>
 * <li>After store(), the referral row: the referrer, the note and consent_declared_at = the application's receipt time
 * (the declaration is made with the submission). The label "Giới thiệu nội bộ" of the unified profile follows from
 * this row; the referred application's source_id stays empty.</li>
 * </ol>
 * The referrer comes from the authenticated caller, never from the form (task 351); the referral row's key takes FOR
 * KEY SHARE on that account, which the referral API already holds FOR NO KEY UPDATE. Lock order: requisition (FOR NO
 * KEY UPDATE, here) → posting FOR SHARE (store()) → the application and candidate arbiters → CV → referral row.
 * <p>
 * Programming errors of the caller are refused before any lock: no transaction (IllegalTransactionStateException,
 * MANDATORY), a read-only transaction or another isolation than READ COMMITTED (IllegalStateException; the candidate
 * link of task 360 and the lock waits need READ COMMITTED), a missing posting id, referral or referrer, a consent not
 * declared or a candidate source on the submission (IllegalArgumentException; the referral API refuses each earlier).
 */
@Service
public class ReferralApplicationStorage {
    private final JdbcTemplate jdbc;
    private final ApplicationStorageService storage;
    private final ApplicationReferralRepository referrals;
    private final Validator validator;

    ReferralApplicationStorage(JdbcTemplate jdbc, ApplicationStorageService storage,
                               ApplicationReferralRepository referrals, Validator validator) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.referrals = referrals;
        this.validator = validator;
    }

    /**
     * What the referrer declares with the submission: who refers (the authenticated caller's account id), the note
     * (as typed; store() normalizes and checks it) and that the candidate agreed to the submission. toString hides the
     * note.
     */
    public record ReferralDeclaration(UUID referrerId, String note, boolean candidateConsent) {
        @Override
        public String toString() {
            return "ReferralDeclaration[" + referrerId + ", consent=" + candidateConsent + ", note redacted]";
        }
    }

    // Must be called in a read-write READ COMMITTED transaction of the caller, through the Spring proxy.
    @Transactional(propagation = Propagation.MANDATORY)
    public StoredApplication store(UUID postingId, ApplicationSubmission submission, ValidatedCv cv, byte[] trackingHash,
                                   ReferralDeclaration referral) {
        requireStoringTransaction();
        if (postingId == null || referral == null || referral.referrerId() == null || !referral.candidateConsent()) {
            throw new IllegalArgumentException("A referral needs a posting, its referrer and the declared consent");
        }
        if (submission != null && submission.sourceId() != null) {
            throw new IllegalArgumentException("A referred application has no candidate source");
        }
        String note = ReferralNote.validated(validator, referral.note());

        // 1. The posting's requisition, without a lock: a posting never changes requisition (V21). Unknown: the
        // answer store() gives.
        var requisitions = jdbc.query("SELECT requisition_id FROM job_postings WHERE id = ?",
                (row, number) -> row.getObject(1, UUID.class), postingId);
        if (requisitions.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "JOB_POSTING_NOT_FOUND", "Tin tuyển dụng không còn nhận hồ sơ.");
        }
        // 2. The 321 rule: the requisition FOR NO KEY UPDATE before anything that makes an application active. Its key
        // is RESTRICT (V21), so the row is there; store() reads its status under this lock.
        jdbc.query("SELECT 1 FROM recruitment_requisitions WHERE id = ? FOR NO KEY UPDATE",
                (row, number) -> row.getInt(1), requisitions.getFirst());
        // 3. TRAN DUC LUONG's storage, joining this transaction: posting checks, 409 duplicate, candidate link, CV.
        StoredApplication stored = storage.store(postingId, submission, cv, trackingHash);
        // 4. The referral row, after the application row its key needs.
        referrals.insert(stored.id(), referral.referrerId(), note, stored.receivedAt());
        return stored;
    }

    // Before any SQL, like ApplicationWithdrawalAccess.lock and the portal's JobPostingApplicationGate. null means the
    // default isolation, READ COMMITTED in PostgreSQL.
    private static void requireStoringTransaction() {
        Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || (isolation != null && isolation != Connection.TRANSACTION_READ_COMMITTED)) {
            throw new IllegalStateException("ReferralApplicationStorage.store needs a read-write READ COMMITTED "
                    + "transaction");
        }
    }
}
