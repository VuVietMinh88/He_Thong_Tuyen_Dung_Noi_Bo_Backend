package vn.ttcs.recruitment.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Task 346 (story S4-06): the outbox of the emails sent after a withdrawal (V35, application_notice_outbox). It
 * copies the lease and retry rules of TRAN DUC LUONG's ApplicationOutboxRepository (V28): claims in their own
 * REQUIRES_NEW READ COMMITTED transaction of at most 5 s with lock_timeout 2 s and statement_timeout 3 s, FOR UPDATE
 * SKIP LOCKED, a 2-minute lease, at most 6 attempts, retries after 60/300/900/3600/21600 s, and an expired lease on the
 * 6th attempt ends as DELIVERY_UNCERTAIN. One change: a claimed recruiter row also leases every other waiting
 * recruiter row of the same recipient whose application belongs to the same requisition, due or not, up to 50 rows
 * with the claimed row first, all under one lease_token. Every later update of a batch (renew, sent, failed, abandon) is fenced by that
 * token and by the row ids it still owns, so a late worker can change none of them.
 * <p>
 * The rows carry no payload: what a mail says is read at send time (candidateNotice, teamMember, withdrawnItems), in
 * the worker's read-only transaction.
 */
@Repository
class ApplicationNoticeRepository {
    static final int BATCH_LIMIT = 50;
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final TransactionTemplate transaction;

    ApplicationNoticeRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate named,
            PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.named = named;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(5);
    }

    // The requisition's PRIMARY recruiter when the account may sign in (enabled, not locked by an administrator: the
    // rule of Account.isAccessAllowed). Read in the withdrawal's transaction, under its requisition lock, which every
    // change of the team takes first: the answer is the PRIMARY at commit time.
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<UUID> activePrimary(UUID requisitionId) {
        return jdbc.query("""
                SELECT rr.recruiter_id FROM requisition_recruiters rr JOIN user_accounts u ON u.id = rr.recruiter_id
                WHERE rr.requisition_id = ? AND rr.assignment_role = 'PRIMARY'
                    AND u.enabled AND u.admin_locked_at IS NULL
                """, (row, number) -> row.getObject(1, UUID.class), requisitionId).stream().findFirst();
    }

    // Joins the withdrawal's transaction: never use REQUIRES_NEW for enqueue (the teammate's rule). The foreign keys
    // take FOR KEY SHARE on the application the caller has locked and on the recipient's account, which an
    // administrator's lock (FOR NO KEY UPDATE) does not block. One row per application and kind; a second one is
    // silently skipped.
    @Transactional(propagation = Propagation.MANDATORY)
    boolean enqueue(UUID id, ApplicationNoticeKind kind, UUID applicationId, UUID recipientId, String messageId,
                    long delaySeconds) {
        return jdbc.update("""
                INSERT INTO application_notice_outbox(id,kind,application_id,recipient_id,message_id,next_attempt_at)
                VALUES (?,?,?,?,?,clock_timestamp()+(? * INTERVAL '1 second'))
                ON CONFLICT ON CONSTRAINT application_notice_outbox_once DO NOTHING
                """, id, kind.name(), applicationId, recipientId, messageId, delaySeconds) == 1;
    }

    Optional<Batch> claim() {
        return transaction.execute(status -> {
            timeouts();
            jdbc.update("""
                    WITH expired AS (
                        SELECT id FROM application_notice_outbox
                        WHERE status='PROCESSING' AND lease_until<=clock_timestamp() AND attempts=6
                        ORDER BY lease_until LIMIT 100 FOR UPDATE SKIP LOCKED
                    )
                    UPDATE application_notice_outbox o SET status='FAILED',lease_token=NULL,lease_until=NULL,
                        last_failure='DELIVERY_UNCERTAIN',updated_at=clock_timestamp()
                    FROM expired e WHERE o.id=e.id
                    """);
            UUID token = UUID.randomUUID();
            var claimed = jdbc.query("""
                    WITH candidate AS (
                        SELECT id FROM application_notice_outbox WHERE attempts<6 AND (
                            (status IN ('PENDING','RETRY_WAIT') AND next_attempt_at<=clock_timestamp())
                            OR (status='PROCESSING' AND lease_until<=clock_timestamp()))
                        ORDER BY next_attempt_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
                    )
                    UPDATE application_notice_outbox o SET status='PROCESSING',attempts=o.attempts+1,
                        lease_token=?,lease_until=clock_timestamp()+INTERVAL '2 minutes',updated_at=clock_timestamp()
                    FROM candidate c WHERE o.id=c.id
                    RETURNING o.id,o.kind,o.application_id,o.recipient_id,o.message_id
                    """, (row, number) -> new Claimed(row.getObject("id", UUID.class),
                    ApplicationNoticeKind.valueOf(row.getString("kind")), row.getObject("application_id", UUID.class),
                    row.getObject("recipient_id", UUID.class), row.getString("message_id")), token);
            if (claimed.isEmpty()) return Optional.<Batch>empty();
            var first = claimed.getFirst();
            var items = new ArrayList<Item>();
            items.add(new Item(first.id(), first.applicationId()));
            // Exhaustive without default: a new kind does not compile until someone decides whether it is grouped.
            switch (first.kind()) {
                case RECRUITER_WITHDRAWAL -> items.addAll(leaseSameRecipientAndRequisition(first, token));
                case CANDIDATE_WITHDRAWAL -> { /* one mail per application */ }
            }
            return Optional.of(new Batch(token, first.kind(), first.messageId(), first.recipientId(),
                    first.applicationId(), List.copyOf(items)));
        });
    }

    // The other waiting recruiter rows of the claimed row's recipient and requisition, due or not (the delay collects
    // them), and rows of a batch whose lease expired (a crashed worker), with attempts left. They count an attempt like
    // the claimed row, since they go out in the same mail. Only outbox rows are locked; the application is only read.
    // The recipient filter matters: after a PRIMARY handover one requisition holds waiting rows of the old and of the
    // new PRIMARY, and each recipient gets only its own.
    private List<Item> leaseSameRecipientAndRequisition(Claimed first, UUID token) {
        return jdbc.query("""
                WITH batch AS (
                    SELECT o.id FROM application_notice_outbox o JOIN job_applications a ON a.id=o.application_id
                    WHERE o.kind='RECRUITER_WITHDRAWAL' AND o.recipient_id=? AND o.id<>? AND o.attempts<6
                        AND a.requisition_id=(SELECT requisition_id FROM job_applications WHERE id=?)
                        AND (o.status IN ('PENDING','RETRY_WAIT')
                            OR (o.status='PROCESSING' AND o.lease_until<=clock_timestamp()))
                    ORDER BY o.next_attempt_at,o.id LIMIT ? FOR UPDATE OF o SKIP LOCKED
                )
                UPDATE application_notice_outbox o SET status='PROCESSING',attempts=o.attempts+1,
                    lease_token=?,lease_until=clock_timestamp()+INTERVAL '2 minutes',updated_at=clock_timestamp()
                FROM batch b WHERE o.id=b.id
                RETURNING o.id,o.application_id
                """, (row, number) -> new Item(row.getObject("id", UUID.class),
                row.getObject("application_id", UUID.class)), first.recipientId(), first.id(), first.applicationId(),
                BATCH_LIMIT - 1, token);
    }

    // Extends the lease of these rows before SMTP; false when any of them is no longer this batch's (lease expired
    // and taken over, or deleted with its application): then nothing is sent.
    boolean renew(Batch batch, Collection<UUID> ids) {
        return fenced(batch, ids, """
                UPDATE application_notice_outbox SET lease_until=clock_timestamp()+INTERVAL '2 minutes',
                    updated_at=clock_timestamp()
                WHERE lease_token=:token AND status='PROCESSING' AND lease_until>clock_timestamp() AND id IN (:ids)
                """, new MapSqlParameterSource());
    }

    boolean sent(Batch batch, Collection<UUID> ids) {
        return fenced(batch, ids, """
                UPDATE application_notice_outbox SET status='SENT',sent_at=clock_timestamp(),
                    updated_at=clock_timestamp(),lease_token=NULL,lease_until=NULL,last_failure=NULL
                WHERE lease_token=:token AND status='PROCESSING' AND id IN (:ids)
                """, new MapSqlParameterSource());
    }

    // A delivery failure: permanent (the recipient was refused) or temporary. Each row keeps its own attempt count, so
    // its retry delay and its last attempt follow the V28 rule row by row.
    boolean failed(Batch batch, Collection<UUID> ids, boolean permanent, String failure) {
        return fenced(batch, ids, """
                UPDATE application_notice_outbox SET
                    status=CASE WHEN CAST(:permanent AS boolean) OR attempts>=6 THEN 'FAILED' ELSE 'RETRY_WAIT' END,
                    last_failure=CASE WHEN CAST(:permanent AS boolean) THEN CAST(:failure AS varchar)
                        WHEN attempts>=6 THEN 'RETRIES_EXHAUSTED' ELSE 'SMTP_TEMPORARY' END,
                    next_attempt_at=clock_timestamp()+(CASE WHEN CAST(:permanent AS boolean) OR attempts>=6 THEN 0
                        ELSE (ARRAY[60,300,900,3600,21600])[attempts] END)*INTERVAL '1 second',
                    updated_at=clock_timestamp(),lease_token=NULL,lease_until=NULL
                WHERE lease_token=:token AND status='PROCESSING' AND id IN (:ids)
                """, new MapSqlParameterSource("permanent", permanent).addValue("failure", failure));
    }

    // Rows that must never be sent (OBSOLETE: no longer withdrawn; RECIPIENT_UNAVAILABLE: the recipient may no longer
    // learn about it): FAILED at once, without SMTP.
    boolean abandon(Batch batch, Collection<UUID> ids, String failure) {
        return fenced(batch, ids, """
                UPDATE application_notice_outbox SET status='FAILED',last_failure=:failure,updated_at=clock_timestamp(),
                    lease_token=NULL,lease_until=NULL
                WHERE lease_token=:token AND status='PROCESSING' AND id IN (:ids)
                """, new MapSqlParameterSource("failure", failure));
    }

    private boolean fenced(Batch batch, Collection<UUID> ids, String sql, MapSqlParameterSource parameters) {
        if (ids.isEmpty()) return true;
        var values = parameters.addValue("token", batch.leaseToken()).addValue("ids", List.copyOf(ids));
        return Boolean.TRUE.equals(transaction.execute(status -> {
            timeouts();
            return named.update(sql, values) == ids.size();
        }));
    }

    // --- Reads at send time: call inside the worker's read-only transaction. ---

    // The candidate's own notice: the application's address and snapshots, and when it was withdrawn; empty when the
    // application is no longer WITHDRAWN (or has no WITHDRAWN event), so nothing is sent.
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<CandidateNotice> candidateNotice(UUID applicationId) {
        return jdbc.query("""
                SELECT a.email, a.posting_title, a.received_at, e.occurred_at
                FROM job_applications a
                JOIN application_stage_events e ON e.application_id = a.id AND e.stage_after = 'WITHDRAWN'
                WHERE a.id = ? AND a.stage = 'WITHDRAWN'
                """, (row, number) -> new CandidateNotice(row.getString(1), row.getString(2),
                row.getTimestamp(3).toInstant(), row.getTimestamp(4).toInstant()), applicationId).stream().findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    Optional<UUID> requisitionOf(UUID applicationId) {
        return jdbc.query("SELECT requisition_id FROM job_applications WHERE id = ?",
                (row, number) -> row.getObject(1, UUID.class), applicationId).stream().findFirst();
    }

    // The recipient as the account is now, when it may still sign in and is still on that requisition's team, PRIMARY
    // or SUPPORTING (a recruiter moved to SUPPORTING still follows the requisition); empty otherwise, also when the
    // account is still on another requisition's team.
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<Recipient> teamMember(UUID accountId, UUID requisitionId) {
        return jdbc.query("""
                SELECT u.email, u.full_name FROM user_accounts u
                WHERE u.id = ? AND u.enabled AND u.admin_locked_at IS NULL AND EXISTS (
                    SELECT 1 FROM requisition_recruiters rr WHERE rr.requisition_id = ? AND rr.recruiter_id = u.id)
                """, (row, number) -> new Recipient(row.getString(1), row.getString(2)), accountId, requisitionId)
                .stream().findFirst();
    }

    // The applications of a recruiter batch that are still WITHDRAWN, with what the mail may name: the posting-title
    // snapshot (written by staff), the withdrawal time and the candidate profile. Nothing the applicant typed is read.
    @Transactional(propagation = Propagation.MANDATORY)
    List<WithdrawnItem> withdrawnItems(Collection<UUID> applicationIds) {
        if (applicationIds.isEmpty()) return List.of();
        return named.query("""
                SELECT a.id, a.posting_title, e.occurred_at, ca.candidate_id
                FROM job_applications a
                JOIN application_stage_events e ON e.application_id = a.id AND e.stage_after = 'WITHDRAWN'
                LEFT JOIN candidate_applications ca ON ca.application_id = a.id
                WHERE a.id IN (:ids) AND a.stage = 'WITHDRAWN'
                ORDER BY e.occurred_at, a.id
                """, new MapSqlParameterSource("ids", List.copyOf(applicationIds)),
                (row, number) -> new WithdrawnItem(row.getObject(1, UUID.class), row.getString(2),
                        row.getTimestamp(3).toInstant(), row.getObject(4, UUID.class)));
    }

    private void timeouts() {
        jdbc.execute("SET LOCAL lock_timeout='2s'"); jdbc.execute("SET LOCAL statement_timeout='3s'");
    }

    private record Claimed(UUID id, ApplicationNoticeKind kind, UUID applicationId, UUID recipientId,
                           String messageId) { }

    // One outbox row of a batch and its application.
    record Item(UUID id, UUID applicationId) { }

    // What one claim leased: one row (a candidate notice) or up to 50 recruiter rows of one recipient and requisition,
    // the claimed row first; the mail takes the claimed row's Message-ID. Ids only.
    record Batch(UUID leaseToken, ApplicationNoticeKind kind, String messageId, UUID recipientId, UUID applicationId,
                 List<Item> items) {
        List<UUID> ids() { return items.stream().map(Item::id).toList(); }
        List<UUID> applicationIds() { return items.stream().map(Item::applicationId).toList(); }
    }

    record CandidateNotice(String email, String postingTitle, Instant receivedAt, Instant withdrawnAt) {
        @Override public String toString() { return "CandidateNotice[personal data redacted]"; }
    }

    record Recipient(String email, String fullName) {
        @Override public String toString() { return "Recipient[personal data redacted]"; }
    }

    record WithdrawnItem(UUID applicationId, String postingTitle, Instant withdrawnAt, UUID candidateId) { }
}
