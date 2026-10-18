package vn.ttcs.recruitment.application;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.common.ApiException;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Task 345 (story S4-06, "Rút hồ sơ qua liên kết trong email xác nhận, có xác nhận lại trước khi thực hiện"): a
 * candidate withdraws one application from the active pipeline with its tracking code, the email it was sent from and
 * confirm: true. Whoever holds both (the confirmation email's link of task 344 carries the code) has that right; nothing
 * else is checked, so a withdrawal is allowed whatever the requisition's or the posting's status.
 * <p>
 * Order: @Valid (400, before any database access) -> canonical code (400) -> client address (400) -> tracking quota,
 * shared with the lookup and committed on its own (429/503) -> one REQUIRES_NEW READ COMMITTED transaction of at most 5
 * s with lock_timeout 2 s and statement_timeout 3 s: ApplicationWithdrawalAccess.lock (the requisition, then the
 * application, FOR NO KEY UPDATE, the credential checked again on the locked row; empty: the lookup's 404), the clock
 * read after the locks, then by the locked stage:
 * <ul>
 * <li>NEW, SCREENING, INTERVIEW, OFFER: the stage becomes WITHDRAWN (only from the locked stage) and one CANDIDATE
 * event is written in the same transaction; 200 with withdrawnAt = now.</li>
 * <li>WITHDRAWN: a repeat; nothing is written; 200 with the time of the first withdrawal (null without an event).</li>
 * <li>HIRED, REJECTED: 409 APPLICATION_NOT_WITHDRAWABLE; nothing is written.</li>
 * </ul>
 * Anything else (a lock or statement timeout, a failed commit) is masked as 503 APPLICATION_TRACKING_UNAVAILABLE, and
 * then nothing of the withdrawal is kept. Two withdrawals of one application run one after the other on the requisition
 * lock; the second is a repeat. A withdrawal and a closure (task 292) run one after the other too, and a withdrawal only
 * lowers the active count. The email stays reserved for that requisition (task 327): withdrawing does not allow
 * applying again.
 */
@Service
public class ApplicationWithdrawalService {
    private final ApplicationClientIpResolver addresses;
    private final PostgresApplicationTrackingQuota quota;
    private final ApplicationWithdrawalAccess access;
    private final ApplicationStageEventRepository events;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final TransactionTemplate transaction;

    ApplicationWithdrawalService(ApplicationClientIpResolver addresses, PostgresApplicationTrackingQuota quota,
            ApplicationWithdrawalAccess access, ApplicationStageEventRepository events, JdbcTemplate jdbc, Clock clock,
            PlatformTransactionManager manager) {
        this.addresses = addresses; this.quota = quota; this.access = access; this.events = events; this.jdbc = jdbc;
        this.clock = clock;
        transaction = new TransactionTemplate(manager);
        // Commits on its own (a caller's transaction must not hold the locks) and reads the newest committed row
        // version under each lock (ApplicationWithdrawalAccess needs READ COMMITTED).
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(5);
    }

    // Call only after @Valid has accepted the request: code and email are then present and well formed, and confirm is
    // true.
    public ApplicationWithdrawalView withdraw(ApplicationWithdrawalRequest request, HttpServletRequest http) {
        try {
            var credential = ApplicationTrackingService.credential(request.trackingCode(), request.email());
            quota.checkAndRecord(ApplicationTrackingService.bucket(addresses, http));
            return Objects.requireNonNull(transaction.execute(status -> withdrawLocked(credential, request.reason())));
        } catch (ApiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            // A lock or statement timeout, a failed commit, anything unexpected: no SQL, class name or submitted value
            // reaches the client, and the transaction kept nothing.
            throw PostgresApplicationTrackingQuota.unavailable();
        }
    }

    private ApplicationWithdrawalView withdrawLocked(TrackingCredential credential, String reason) {
        jdbc.execute("SET LOCAL lock_timeout = '2s'");
        jdbc.execute("SET LOCAL statement_timeout = '3s'");
        var locked = access.lock(credential).orElseThrow(ApplicationTrackingService::notFound);
        // After the last lock wait: the withdrawal happens when its locks were granted.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        // Exhaustive without default: a new stage does not compile until someone decides whether it can be withdrawn.
        return switch (locked.stage()) {
            case NEW, SCREENING, INTERVIEW, OFFER -> {
                if (!events.moveToWithdrawn(locked.id(), locked.stage())) {
                    throw new IllegalStateException("The locked application changed its stage");
                }
                events.recordCandidateWithdrawal(locked.id(), locked.stage(), reason, now);
                yield ApplicationWithdrawalView.of(locked.postingTitle(), locked.receivedAt(), now);
            }
            case WITHDRAWN -> ApplicationWithdrawalView.of(locked.postingTitle(), locked.receivedAt(),
                    events.withdrawnAt(locked.id()).orElse(null));
            case HIRED, REJECTED -> throw new ApiException(HttpStatus.CONFLICT, "APPLICATION_NOT_WITHDRAWABLE",
                    "Hồ sơ đã có kết quả nên không thể rút nữa.");
        };
    }
}
