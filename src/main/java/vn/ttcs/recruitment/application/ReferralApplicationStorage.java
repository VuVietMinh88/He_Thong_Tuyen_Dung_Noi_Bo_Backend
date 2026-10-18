package vn.ttcs.recruitment.application;

import jakarta.validation.Validator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
 * class adds the referral data around it:
 * <ol>
 * <li>The note is checked before any SQL: Windows line breaks become \n, a note without a visible character counts as
 * none, it is required, at most 2000 characters and plain text (@PlainText multiline): 400 VALIDATION_ERROR with
 * fieldErrors referralNote.</li>
 * <li>store() uses JobPostingApplicationGate: requisition FOR NO KEY UPDATE, then posting FOR SHARE. Public intake and
 * referrals therefore wait for closure, pause, unpublish and expiry in the same order, and reject an expired posting
 * with 409 JOB_POSTING_EXPIRED. The supplied caller check runs after waits for authenticated referrals.</li>
 * <li>After store(), the referral row: the referrer, the note and consent_declared_at = the application's receipt time
 * (the declaration is made with the submission). The label "Giới thiệu nội bộ" of the unified profile follows from
 * this row; the referred application's source_id stays empty.</li>
 * </ol>
 * The referrer comes from the authenticated caller, never from the form (task 351); the referral row's key takes FOR
 * KEY SHARE on that account, which the referral API already holds FOR NO KEY UPDATE. Lock order: requisition (FOR NO
 * KEY UPDATE, gate) → posting FOR SHARE (gate) → the application and candidate arbiters → CV → referral row.
 * <p>
 * Programming errors of the caller are refused before any lock: no transaction (IllegalTransactionStateException,
 * MANDATORY), a read-only transaction or another isolation than READ COMMITTED (IllegalStateException; the candidate
 * link of task 360 and the lock waits need READ COMMITTED), a missing posting id, referral or referrer, a consent not
 * declared or a candidate source on the submission (IllegalArgumentException; the referral API refuses each earlier).
 */
@Service
public class ReferralApplicationStorage {
    private final ApplicationStorageService storage;
    private final ApplicationReferralRepository referrals;
    private final Validator validator;

    ReferralApplicationStorage(ApplicationStorageService storage,
                               ApplicationReferralRepository referrals, Validator validator) {
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
        return store(postingId, submission, cv, trackingHash, referral, () -> { });
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public StoredApplication store(UUID postingId, ApplicationSubmission submission, ValidatedCv cv, byte[] trackingHash,
                                   ReferralDeclaration referral, Runnable callerCheck) {
        requireStoringTransaction();
        if (postingId == null || referral == null || referral.referrerId() == null || !referral.candidateConsent()) {
            throw new IllegalArgumentException("A referral needs a posting, its referrer and the declared consent");
        }
        if (submission != null && submission.sourceId() != null) {
            throw new IllegalArgumentException("A referred application has no candidate source");
        }
        String note = ReferralNote.validated(validator, referral.note());

        // Storage owns the shared posting gate and does not start a separate transaction.
        StoredApplication stored = storage.store(postingId, submission, cv, trackingHash, callerCheck);
        // The referral row, after the application row its key needs.
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
