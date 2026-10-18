package vn.ttcs.recruitment.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Connection;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Task 344 (story S4-06, "Rút hồ sơ qua liên kết trong email xác nhận"): verifies a withdrawal link and locks the one
 * application it acts on. The link is the confirmation email's own tracking code
 * ({@link ApplicationConfirmationSettings#withdrawalUrl}), so the only right it proves is holding that application's
 * code together with the email it was sent from; nothing else (an account, a session, a second token) is involved.
 * <p>
 * Contract for the withdrawal (task 345) and, later, any other write that changes an application's stage: call
 * {@link #lock} first, in your own read-write READ COMMITTED transaction, before writing anything; the locks last
 * until that transaction ends. Lock order (the house order): the application's requisition FOR NO KEY UPDATE, then the
 * application row FOR NO KEY UPDATE. A withdrawal therefore runs one after another with every closure, pause and team
 * change of its requisition (they lock the requisition first too), and a stage change of Sprint 5 can follow the same
 * path. The credential is checked again on the locked row: under READ COMMITTED a row lock that waited reads the newest
 * committed version, so a code revoked, or a stage changed, while this call waited is seen. Read the clock after this
 * call, never before. The result carries internal ids: never return them in a candidate-facing body or email (task
 * 316).
 * <p>
 * An unknown code, a wrong email and a revoked code all give an empty result, through the same statements, so the
 * caller answers them with one 404. An empty result after the requisition was locked (the code was revoked while this
 * call waited) keeps that lock until the caller's transaction ends; nothing is written.
 */
@Component
class ApplicationWithdrawalAccess {
    // The application under the locks: its id and requisition (internal), its stage as locked, and the posting title
    // and receipt time it was stored with (both snapshots of task 325/326, safe to show the candidate).
    record LockedApplication(UUID id, UUID requisitionId, ApplicationStage stage, String postingTitle,
                             Instant receivedAt) { }

    private final JdbcTemplate jdbc;

    ApplicationWithdrawalAccess(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * Locks the application whose code hash and normalized email are those of the credential, and whose code is not
     * revoked, and returns it as locked; otherwise returns empty. Writes nothing. Throws
     * IllegalTransactionStateException without a transaction (MANDATORY) and IllegalStateException in a read-only
     * transaction or one with another isolation than READ COMMITTED, before any SQL.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<LockedApplication> lock(TrackingCredential credential) {
        requireLockingTransaction();
        // 1. Which rows to lock: read without a lock, through the UNIQUE index on the hash. The requisition of an
        // application never changes, so it may be read before its lock.
        var found = jdbc.query("""
                SELECT id, requisition_id FROM job_applications
                WHERE tracking_code_hash = ? AND normalized_email = ? AND tracking_revoked_at IS NULL
                """, (row, number) -> new Found(row.getObject(1, UUID.class), row.getObject(2, UUID.class)),
                credential.hash(), credential.normalizedEmail());
        if (found.isEmpty()) return Optional.empty();
        UUID application = found.getFirst().id(), requisition = found.getFirst().requisitionId();
        // 2. The requisition first, as every closure, pause and team change does. The application's key is RESTRICT
        // (V26) and no API deletes a requisition, so the row is always there.
        if (jdbc.queryForList("SELECT 1 FROM recruitment_requisitions WHERE id = ? FOR NO KEY UPDATE", Integer.class,
                requisition).size() != 1) {
            throw new IllegalStateException("The application's requisition was not locked");
        }
        // 3. Then the application, by id and with the whole credential again: a wait above may have let a revocation
        // commit. One table, no join, so the row read under the lock is the newest committed version (READ
        // COMMITTED), stage included.
        return jdbc.query("""
                SELECT id, requisition_id, stage, posting_title, received_at FROM job_applications
                WHERE id = ? AND tracking_code_hash = ? AND normalized_email = ? AND tracking_revoked_at IS NULL
                FOR NO KEY UPDATE
                """, (row, number) -> new LockedApplication(row.getObject(1, UUID.class), row.getObject(2, UUID.class),
                        ApplicationStage.valueOf(row.getString(3)), row.getString(4), row.getTimestamp(5).toInstant()),
                application, credential.hash(), credential.normalizedEmail()).stream().findFirst();
    }

    private record Found(UUID id, UUID requisitionId) { }

    // A programming error of the caller, refused before any SQL (the JobPostingApplicationGate precedent of task 321).
    // In REPEATABLE READ or SERIALIZABLE, a row changed after the transaction's snapshot cannot be locked (PostgreSQL
    // raises a serialization failure instead of reading the new version), and PostgreSQL refuses row locks in a
    // read-only transaction. null means the default isolation, READ COMMITTED in PostgreSQL.
    private static void requireLockingTransaction() {
        Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || (isolation != null && isolation != Connection.TRANSACTION_READ_COMMITTED)) {
            throw new IllegalStateException("ApplicationWithdrawalAccess.lock needs a read-write READ COMMITTED "
                    + "transaction");
        }
    }
}
