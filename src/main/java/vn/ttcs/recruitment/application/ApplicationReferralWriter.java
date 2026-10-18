package vn.ttcs.recruitment.application;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.auth.AuthSession;
import vn.ttcs.recruitment.auth.AuthSessionRepository;
import vn.ttcs.recruitment.auth.AuthenticationFailureException;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.security.PermissionService;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Task 351 (story S4-07): the write of one referral, after the form, the CV and the attempt quota were checked
 * (ApplicationReferralService). One REQUIRES_NEW READ COMMITTED transaction of at most 5 s with lock_timeout 2 s and
 * statement_timeout 3 s, in the house's lock order:
 * <ol>
 * <li>the caller's account, then its session, FOR NO KEY UPDATE (PESSIMISTIC_WRITE, the avatar's lockCallerForWrite):
 * an administrator lock, a logout or a role change waits for this write; then the token, the session and
 * SELF_PROFILE_WRITE are checked again (401 SESSION_INVALID, 403);</li>
 * <li>the candidate's email must not be the caller's own (400 REFERRAL_SELF_NOT_ALLOWED);</li>
 * <li>a new tracking code: 32 SecureRandom bytes, Base64URL without padding, stored as SHA-256 of the bytes (as
 * PostgresApplicationReceiptWriter);</li>
 * <li>ReferralApplicationStorage.store (task 350), joining this transaction: the shared posting gate locks the
 * requisition FOR NO KEY UPDATE then the posting FOR SHARE. A callback checks the caller after each wait before
 * checking APPROVED/PUBLISHED/deadline (404 unavailable, 409 expired). Storage runs the shared duplicate rule (409),
 * links the candidate and writes the CV; then the referral row. The callback runs again after catalog/candidate waits;</li>
 * <li>the caller is checked again (store() may have waited on the posting);</li>
 * <li>the candidate's confirmation is queued exactly as the receipt writer queues it (encrypted payload, joined
 * enqueue): the candidate receives the tracking code and the withdrawal link by email, the referrer never sees them.
 * The confirmation worker adds the referral paragraph at send time (ApplicationReferralLookup).</li>
 * </ol>
 * The referrer is the authenticated caller, never a form field. Not PostgresApplicationReceiptWriter.accept: it forces
 * REQUIRES_NEW, which would separate the application from the referral transaction.
 * The token bytes are zeroed in finally.
 */
@Component
public class ApplicationReferralWriter {
    private final AccountRepository accounts;
    private final AuthSessionRepository sessions;
    private final PermissionService permissions;
    private final ReferralApplicationStorage referrals;
    private final ApplicationOutboxRepository outbox;
    private final ApplicationOutboxCrypto crypto;
    private final ApplicationConfirmationSettings settings;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final SecureRandom random = new SecureRandom();

    ApplicationReferralWriter(AccountRepository accounts, AuthSessionRepository sessions, PermissionService permissions,
            ReferralApplicationStorage referrals, ApplicationOutboxRepository outbox, ApplicationOutboxCrypto crypto,
            ApplicationConfirmationSettings settings, JdbcTemplate jdbc, Clock clock, PlatformTransactionManager manager) {
        this.accounts = accounts; this.sessions = sessions; this.permissions = permissions; this.referrals = referrals;
        this.outbox = outbox; this.crypto = crypto; this.settings = settings; this.jdbc = jdbc; this.clock = clock;
        transaction = new TransactionTemplate(manager);
        // Commits on its own and reads the newest committed row version under each lock (store() needs READ COMMITTED).
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(5);
    }

    ApplicationReferralView refer(Jwt jwt, ApplicationMultipartDecoder.ReferralForm form, ValidatedCv cv) {
        if (!settings.ready()) throw ApplicationReferralService.unavailable();
        byte[] token = new byte[32];
        random.nextBytes(token);
        try {
            String code = Base64.getUrlEncoder().withoutPadding().encodeToString(token);
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(token);
            return Objects.requireNonNull(transaction.execute(status -> referLocked(jwt, form, cv, code, hash)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable");
        } finally {
            Arrays.fill(token, (byte) 0);
        }
    }

    private ApplicationReferralView referLocked(Jwt jwt, ApplicationMultipartDecoder.ReferralForm form, ValidatedCv cv,
                                                String code, byte[] hash) {
        // These limits apply only to this transaction, the storage and the enqueue included.
        jdbc.execute("SET LOCAL lock_timeout = '2s'");
        jdbc.execute("SET LOCAL statement_timeout = '3s'");
        // 1. Account, then session, then the caller checked again.
        Referrer referrer = lockReferrer(jwt);
        // 2. Nobody refers themselves: the account's email, normalized like a candidate's.
        String own = ApplicationSubmission.normalizeEmail(referrer.email());
        if (own != null && own.equals(form.submission().normalizedEmail())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REFERRAL_SELF_NOT_ALLOWED", "Không thể tự giới thiệu chính mình.",
                    Map.of("email", "Không thể tự giới thiệu chính mình."));
        }
        // 3. The application, its CV, candidate link and referral row share the gate and this transaction. The
        // callback refreshes time-dependent session/token validity and current permissions after every lock wait.
        StoredApplication stored = referrals.store(form.postingId(), form.submission(), cv, hash,
                new ReferralApplicationStorage.ReferralDeclaration(referrer.id(), form.note(), true),
                () -> requireStillReferrer(jwt, referrer));
        // 4. One final caller check before enqueueing the confirmation.
        requireStillReferrer(jwt, referrer);
        // 5. The candidate's confirmation, as the intake queues it; the stored values are what the 201 shows.
        var saved = jdbc.queryForMap("""
                SELECT a.full_name, a.posting_title, r.note
                FROM job_applications a JOIN application_referrals r ON r.application_id = a.id WHERE a.id = ?
                """, stored.id());
        String title = (String) saved.get("posting_title");
        var receipt = new ApplicationReceipt(stored.id(), stored.receivedAt(), code, settings.trackingUrl(code));
        UUID outboxId = UUID.randomUUID();
        var payload = new ApplicationConfirmationPayload(form.submission().email(), form.submission().fullName(), title,
                receipt.receivedAt(), code, receipt.trackingUrl(), settings.from());
        outbox.enqueue(outboxId, stored.id(), settings.messageId(outboxId), crypto.encrypt(outboxId, stored.id(), payload));
        return ApplicationReferralView.of(stored.id(), (String) saved.get("full_name"), title, stored.receivedAt(),
                stored.stage(), (String) saved.get("note"));
    }

    private Referrer lockReferrer(Jwt jwt) {
        UUID userId;
        UUID sessionId;
        try {
            userId = UUID.fromString(jwt.getSubject());
            sessionId = UUID.fromString(jwt.getId());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw AuthenticationFailureException.sessionInvalid();
        }
        // Same lock order as the profile, avatar and password services: the caller's account first, then its session.
        Account account = accounts.findByIdForUpdate(userId).filter(Account::isAccessAllowed)
                .orElseThrow(AuthenticationFailureException::sessionInvalid);
        AuthSession session = sessions.findByIdForUpdate(sessionId)
                .orElseThrow(AuthenticationFailureException::sessionInvalid);
        var referrer = new Referrer(userId, account.getEmail(), session);
        requireStillReferrer(jwt, referrer);
        return referrer;
    }

    // The account and the session are locked by this transaction; the token's expiry and the session's are times,
    // and the permission is not locked (a role's grants may change): all three are read again here.
    private void requireStillReferrer(Jwt jwt, Referrer referrer) {
        Instant now = clock.instant();
        if (jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(now)
                || !referrer.session().getUserId().equals(referrer.id()) || !referrer.session().isActive(now)) {
            throw AuthenticationFailureException.sessionInvalid();
        }
        if (!permissions.forUser(referrer.id()).contains("SELF_PROFILE_WRITE")) {
            throw new AccessDeniedException("A referral requires SELF_PROFILE_WRITE");
        }
    }

    private record Referrer(UUID id, String email, AuthSession session) {
        @Override public String toString() { return "Referrer[" + id + ", email redacted]"; }
    }
}
