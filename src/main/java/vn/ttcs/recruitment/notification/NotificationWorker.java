package vn.ttcs.recruitment.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Array;
import java.sql.SQLException;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Task 544 (story S8-03): turns committed notification events into notifications after the producer's transaction,
 * so approving, assigning or scheduling never waits for it. A claim takes one due event with a lease (FOR UPDATE SKIP
 * LOCKED, so several instances never take the same one); the event's notifications and its DONE status are then
 * written in one transaction fenced by the lease token, so a crash or an expired lease leaves either everything or
 * nothing, and a second run of the same event inserts nothing new (unique recipient + key). A failure waits and
 * tries again, up to six times.
 */
@Component
public class NotificationWorker {
    private static final Logger LOG = LoggerFactory.getLogger(NotificationWorker.class);
    private static final long[] RETRY_SECONDS = {30, 120, 600, 1800, 7200};
    private final JdbcTemplate jdbc;
    private final NotificationPlanner planner;
    private final NotificationDispatch dispatch;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public NotificationWorker(JdbcTemplate jdbc, NotificationPlanner planner, NotificationDispatch dispatch, Clock clock,
                              PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.planner = planner;
        this.dispatch = dispatch;
        this.clock = clock;
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transactions.setTimeout(10);
    }

    @Scheduled(initialDelayString = "${app.notifications.poll-delay-ms:5000}",
            fixedDelayString = "${app.notifications.poll-delay-ms:5000}")
    public void poll() {
        try {
            for (int i = 0; i < 20 && processNext(); i++) { /* one bounded batch per run */ }
        } catch (RuntimeException exception) {
            LOG.warn("Notification processing failed; will retry on the next run");
        }
    }

    public boolean processNext() {
        Optional<Claimed> claimed = claim();
        if (claimed.isEmpty()) return false;
        Claimed event = claimed.get();
        try {
            transactions.executeWithoutResult(status -> {
                timeouts();
                // The row stays locked until the notifications and DONE commit together.
                boolean owned = !jdbc.queryForList("""
                        SELECT id FROM notification_events
                        WHERE id = ? AND status = 'PROCESSING' AND lease_token = ? FOR UPDATE
                        """, UUID.class, event.event().id(), event.lease()).isEmpty();
                if (!owned) return;
                var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
                for (var planned : planner.plan(event.event())) dispatch.deliver(event.event().id(), planned, now);
                jdbc.update("""
                        UPDATE notification_events SET status = 'DONE', processed_at = clock_timestamp(),
                            lease_token = NULL, lease_until = NULL, last_failure = NULL
                        WHERE id = ? AND lease_token = ?
                        """, event.event().id(), event.lease());
            });
        } catch (RuntimeException failure) {
            failed(event);
        }
        return true;
    }

    private Optional<Claimed> claim() {
        return transactions.execute(status -> {
            timeouts();
            jdbc.update("""
                    WITH expired AS (
                        SELECT id FROM notification_events
                        WHERE status = 'PROCESSING' AND lease_until <= clock_timestamp() AND attempts = 6
                        ORDER BY lease_until LIMIT 100 FOR UPDATE SKIP LOCKED
                    )
                    UPDATE notification_events n SET status = 'FAILED', lease_token = NULL, lease_until = NULL,
                        last_failure = 'LEASE_EXPIRED'
                    FROM expired e WHERE n.id = e.id
                    """);
            UUID lease = UUID.randomUUID();
            return jdbc.query("""
                    WITH candidate AS (
                        SELECT id FROM notification_events WHERE attempts < 6 AND (
                            (status IN ('PENDING', 'RETRY_WAIT') AND next_attempt_at <= clock_timestamp())
                            OR (status = 'PROCESSING' AND lease_until <= clock_timestamp()))
                        ORDER BY next_attempt_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
                    )
                    UPDATE notification_events n SET status = 'PROCESSING', attempts = n.attempts + 1,
                        lease_token = ?, lease_until = clock_timestamp() + INTERVAL '2 minutes'
                    FROM candidate c WHERE n.id = c.id
                    RETURNING n.id, n.event_type, n.entity_type, n.entity_id, n.recipient_ids, n.attempts
                    """, (row, number) -> new Claimed(new NotificationPlanner.Event(row.getObject("id", UUID.class),
                    NotificationType.valueOf(row.getString("event_type")),
                    NotificationLink.Type.valueOf(row.getString("entity_type")), row.getObject("entity_id", UUID.class),
                    uuids(row.getArray("recipient_ids"))), lease, row.getInt("attempts")), lease).stream().findFirst();
        });
    }

    private void failed(Claimed event) {
        boolean last = event.attempts() >= 6;
        transactions.executeWithoutResult(status -> {
            timeouts();
            jdbc.update("""
                    UPDATE notification_events SET status = ?, last_failure = 'PROCESSING_FAILED',
                        next_attempt_at = clock_timestamp() + (? * INTERVAL '1 second'),
                        lease_token = NULL, lease_until = NULL
                    WHERE id = ? AND status = 'PROCESSING' AND lease_token = ?
                    """, last ? "FAILED" : "RETRY_WAIT", last ? 0 : RETRY_SECONDS[event.attempts() - 1],
                    event.event().id(), event.lease());
        });
    }

    private void timeouts() {
        jdbc.execute("SET LOCAL lock_timeout='2s'");
        jdbc.execute("SET LOCAL statement_timeout='5s'");
    }

    static List<UUID> uuids(Array array) throws SQLException {
        return Arrays.stream((Object[]) array.getArray()).map(value -> (UUID) value).toList();
    }

    private record Claimed(NotificationPlanner.Event event, UUID lease, int attempts) {
    }
}
