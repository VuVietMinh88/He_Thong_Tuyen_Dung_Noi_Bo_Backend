package vn.ttcs.recruitment.notification;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.UUID;

/**
 * Task 544 (story S8-03): records that something happened that someone must hear about, in the producer's own
 * transaction (MANDATORY): one short INSERT, so the business write is not slowed down, and nothing is notified unless
 * the business change commits. NotificationWorker resolves recipients and texts later; a repeated event resolves to
 * the same notification keys, so nobody is notified twice for the same state.
 */
@Component
public class NotificationEvents {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public NotificationEvents(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** The worker resolves the recipients from the entity's current state. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(NotificationType type, NotificationLink.Type entityType, UUID entityId) {
        publish(type, entityType, entityId, java.util.List.of());
    }

    /** The producer names the recipients (at most 100); the worker still checks each one against current data. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(NotificationType type, NotificationLink.Type entityType, UUID entityId,
                        Collection<UUID> recipients) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(entityId, "entityId");
        var distinct = new LinkedHashSet<UUID>();
        for (UUID recipient : recipients) distinct.add(Objects.requireNonNull(recipient, "recipient"));
        if (distinct.size() > 100) throw new IllegalArgumentException("At most 100 named recipients per event");
        jdbc.update("""
                INSERT INTO notification_events(id, event_type, entity_type, entity_id, recipient_ids, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, ps -> {
            ps.setObject(1, UUID.randomUUID());
            ps.setString(2, type.name());
            ps.setString(3, entityType.name());
            ps.setObject(4, entityId);
            ps.setArray(5, ps.getConnection().createArrayOf("uuid", distinct.toArray()));
            ps.setTimestamp(6, Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MICROS)));
        });
    }
}
