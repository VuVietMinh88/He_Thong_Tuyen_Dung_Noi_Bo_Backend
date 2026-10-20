package vn.ttcs.recruitment.invitation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.application.ApplicationStage;
import vn.ttcs.recruitment.interview.InterviewView;
import vn.ttcs.recruitment.security.AccessScope;
import vn.ttcs.recruitment.security.PermissionModule;
import vn.ttcs.recruitment.security.PermissionService;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Small fenced transactions; the SMTP connection is never held inside one of these transactions. */
@Repository
public class InterviewInvitationOutboxRepository {
    private static final long[] RETRY_SECONDS = {60, 300, 900, 3600, 21600};
    private static final Set<String> PERMANENT_FAILURES = Set.of("RECIPIENT_REFUSED", "PAYLOAD_INVALID");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final PermissionService permissions;

    public InterviewInvitationOutboxRepository(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock,
                                               PermissionService permissions) {
        this.jdbc = jdbc; this.clock = clock; this.permissions = permissions;
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transactions.setTimeout(5);
    }

    /** False when a row with this dedupe key already exists (task 533 then records no second email message). */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean enqueue(UUID id, InterviewView view, UUID invitation, UUID recipient, String kind, String dedupe,
                        InterviewInvitationCrypto.Encrypted encrypted) {
        return enqueue(id, view.id(), view.applicationId(), view.version(), invitation, recipient, kind, dedupe, encrypted);
    }

    /** Task 539: the same row for a rebuilt email, when the caller holds the interview's ids rather than its view. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean enqueue(UUID id, UUID interviewId, UUID applicationId, long version, UUID invitation, UUID recipient,
                           String kind, String dedupe, InterviewInvitationCrypto.Encrypted encrypted) {
        return jdbc.update("""
                INSERT INTO interview_invitation_outbox(id,interview_id,application_id,schedule_version,invitation_id,
                    recipient_user_id,kind,dedupe_key,key_id,nonce,ciphertext)
                VALUES (?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT (dedupe_key) DO NOTHING
                """, id, interviewId, applicationId, version, invitation, recipient, kind, dedupe,
                encrypted.keyId(), encrypted.nonce(), encrypted.ciphertext()) == 1;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelPending(UUID interview) {
        jdbc.update("""
                UPDATE interview_invitation_outbox o SET status='CANCELLED',last_failure='OBSOLETE',
                    key_id=NULL,nonce=NULL,ciphertext=NULL,lease_token=NULL,lease_until=NULL,updated_at=clock_timestamp()
                WHERE interview_id=? AND status IN ('PENDING','PROCESSING','RETRY_WAIT')
                    AND (kind<>'REMOVED' OR EXISTS (SELECT 1 FROM interview_participants p
                        WHERE p.interview_id=o.interview_id AND p.user_id=o.recipient_user_id AND p.active))
                """, interview);
    }

    Optional<Delivery> claim() {
        return transactions.execute(status -> {
            timeouts();
            jdbc.update("""
                    WITH expired AS (
                        SELECT id FROM interview_invitation_outbox
                        WHERE status='PROCESSING' AND lease_until<=clock_timestamp() AND attempts=6
                        ORDER BY lease_until LIMIT 100 FOR UPDATE SKIP LOCKED
                    )
                    UPDATE interview_invitation_outbox o SET status='FAILED',lease_token=NULL,lease_until=NULL,
                        last_failure='DELIVERY_UNCERTAIN',key_id=NULL,nonce=NULL,ciphertext=NULL,updated_at=clock_timestamp()
                    FROM expired e WHERE o.id=e.id
                    """);
            UUID lease = UUID.randomUUID();
            return jdbc.query("""
                    WITH candidate AS (
                        SELECT id FROM interview_invitation_outbox WHERE attempts<6 AND (
                            (status IN ('PENDING','RETRY_WAIT') AND next_attempt_at<=clock_timestamp())
                            OR (status='PROCESSING' AND lease_until<=clock_timestamp()))
                        ORDER BY next_attempt_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
                    )
                    UPDATE interview_invitation_outbox o SET status='PROCESSING',attempts=o.attempts+1,
                        lease_token=?,lease_until=clock_timestamp()+INTERVAL '2 minutes',updated_at=clock_timestamp()
                    FROM candidate c WHERE o.id=c.id
                    RETURNING o.id,o.interview_id,o.application_id,o.schedule_version,o.invitation_id,o.recipient_user_id,
                        o.kind,o.key_id,o.nonce,o.ciphertext,o.lease_token,o.attempts
                    """, (row, number) -> new Delivery(row.getObject("id", UUID.class),
                    row.getObject("interview_id", UUID.class), row.getObject("application_id", UUID.class),
                    row.getLong("schedule_version"), row.getObject("invitation_id", UUID.class),
                    row.getObject("recipient_user_id", UUID.class), row.getString("kind"), row.getString("key_id"),
                    row.getBytes("nonce"), row.getBytes("ciphertext"), row.getObject("lease_token", UUID.class),
                    row.getInt("attempts")), lease).stream().findFirst();
        });
    }

    /** Recheck immediately before sending. A change after SMTP accepts a message cannot recall that message. */
    String unavailableReason(Delivery delivery, InterviewMailPayload payload) {
        return transactions.execute(status -> {
            timeouts();
            var rows = jdbc.query("""
                    SELECT i.version,i.status,i.starts_at,a.email,a.stage,r.status AS requisition_status,
                        v.revoked_at,v.expires_at,v.schedule_version AS invitation_version
                    FROM interview_sessions i JOIN job_applications a ON a.id=i.application_id
                    JOIN recruitment_requisitions r ON r.id=a.requisition_id
                    LEFT JOIN interview_invitations v ON v.id=? AND v.interview_id=i.id
                    WHERE i.id=? AND i.application_id=?
                    """, (row, number) -> new State(row.getLong("version"), row.getString("status"),
                    row.getTimestamp("starts_at").toInstant(), row.getString("email"), row.getString("stage"),
                    row.getString("requisition_status"), row.getTimestamp("revoked_at") != null,
                    row.getTimestamp("expires_at") == null ? null : row.getTimestamp("expires_at").toInstant(),
                    row.getLong("invitation_version")), delivery.invitationId(), delivery.interviewId(), delivery.applicationId());
            if (rows.isEmpty() || !Objects.equals(payload.kind(), delivery.kind())) return "OBSOLETE";
            State current = rows.getFirst();
            boolean removed = "REMOVED".equals(delivery.kind());
            boolean cancelled = "CANCELLED".equals(delivery.kind());
            if (!removed && current.version() != delivery.version()) return "OBSOLETE";
            if (cancelled && !"CANCELLED".equals(current.status())) return "OBSOLETE";
            if (!removed && !cancelled && (!"SCHEDULED".equals(current.status())
                    || !current.startsAt().isAfter(clock.instant()) || !"APPROVED".equals(current.requisitionStatus())
                    || !ApplicationStage.valueOf(current.stage()).active())) return "OBSOLETE";
            if (delivery.recipientUserId() == null) {
                if (!Objects.equals(current.email(), payload.email())) return "RECIPIENT_UNAVAILABLE";
                if (!cancelled && (current.revoked() || current.expiresAt() == null
                        || !current.expiresAt().isAfter(clock.instant())
                        || current.invitationVersion() != current.version())) return "OBSOLETE";
                return null;
            }
            if (!removed && !cancelled) {
                var grants = permissions.forUser(delivery.recipientUserId());
                if (AccessScope.read(grants, PermissionModule.INTERVIEWS) == AccessScope.NONE
                        || AccessScope.write(grants, PermissionModule.EVALUATIONS) == AccessScope.NONE) {
                    return "RECIPIENT_UNAVAILABLE";
                }
            }
            Boolean available = jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM user_accounts u
                        WHERE u.id=? AND u.enabled AND u.admin_locked_at IS NULL AND u.email=?
                            AND EXISTS (SELECT 1 FROM interview_participants p
                                WHERE p.interview_id=? AND p.user_id=u.id AND (? OR p.active=?)))
                    """, Boolean.class, delivery.recipientUserId(), payload.email(), delivery.interviewId(),
                    cancelled, !removed);
            return Boolean.TRUE.equals(available) ? null : "RECIPIENT_UNAVAILABLE";
        });
    }

    boolean renew(Delivery delivery) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            timeouts();
            return jdbc.update("""
                    UPDATE interview_invitation_outbox SET lease_until=clock_timestamp()+INTERVAL '2 minutes',
                        updated_at=clock_timestamp()
                    WHERE id=? AND status='PROCESSING' AND lease_token=? AND lease_until>clock_timestamp()
                    """, delivery.id(), delivery.leaseToken()) == 1;
        }));
    }

    boolean sent(Delivery delivery) {
        return finish(delivery, "SENT", null);
    }

    boolean abandon(Delivery delivery, String reason) {
        if (!Set.of("OBSOLETE", "RECIPIENT_UNAVAILABLE").contains(reason)) throw new IllegalArgumentException("Invalid failure code");
        return finish(delivery, "CANCELLED", reason);
    }

    boolean failed(Delivery delivery, boolean permanent, String failure) {
        if (permanent && !PERMANENT_FAILURES.contains(failure)) throw new IllegalArgumentException("Invalid failure code");
        boolean terminal = permanent || delivery.attempts() >= 6;
        String code = permanent ? failure : terminal ? "RETRIES_EXHAUSTED" : "SMTP_TEMPORARY";
        if (terminal) return finish(delivery, "FAILED", code);
        long delay = RETRY_SECONDS[delivery.attempts() - 1];
        return Boolean.TRUE.equals(transactions.execute(status -> {
            timeouts();
            return jdbc.update("""
                    UPDATE interview_invitation_outbox SET status='RETRY_WAIT',last_failure=?,
                        next_attempt_at=clock_timestamp()+(? * INTERVAL '1 second'),updated_at=clock_timestamp(),
                        lease_token=NULL,lease_until=NULL
                    WHERE id=? AND status='PROCESSING' AND lease_token=?
                    """, code, delay, delivery.id(), delivery.leaseToken()) == 1;
        }));
    }

    private boolean finish(Delivery delivery, String result, String failure) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            timeouts();
            return jdbc.update("""
                    UPDATE interview_invitation_outbox SET status=?,last_failure=?,updated_at=clock_timestamp(),
                        sent_at=CASE WHEN ?='SENT' THEN clock_timestamp() ELSE NULL END,
                        lease_token=NULL,lease_until=NULL,key_id=NULL,nonce=NULL,ciphertext=NULL
                    WHERE id=? AND status='PROCESSING' AND lease_token=?
                    """, result, failure, result, delivery.id(), delivery.leaseToken()) == 1;
        }));
    }

    private void timeouts() {
        jdbc.execute("SET LOCAL lock_timeout='2s'");
        jdbc.execute("SET LOCAL statement_timeout='3s'");
    }

    record Delivery(UUID id, UUID interviewId, UUID applicationId, long version, UUID invitationId,
                    UUID recipientUserId, String kind, String keyId, byte[] nonce, byte[] ciphertext,
                    UUID leaseToken, int attempts) {
        @Override public String toString() { return "InterviewDelivery[redacted]"; }
    }
    private record State(long version, String status, Instant startsAt, String email, String stage,
                         String requisitionStatus, boolean revoked, Instant expiresAt, long invitationVersion) { }
}
