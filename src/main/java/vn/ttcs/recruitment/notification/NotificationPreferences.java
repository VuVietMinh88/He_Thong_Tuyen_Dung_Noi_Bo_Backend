package vn.ttcs.recruitment.notification;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Task 549 (story S8-04): reads and stores notification_preferences (V66_8). enabled is the rule every delivery uses:
 * a mandatory type is always on, whatever is stored; otherwise the stored choice, and on when nothing is stored.
 */
@Component
public class NotificationPreferences {
    public enum Channel { IN_APP, EMAIL }

    /** One user's two channels for one type. */
    public record Choice(boolean inApp, boolean email) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public NotificationPreferences(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static boolean enabled(NotificationType type, Boolean stored) {
        return type.mandatory() || stored == null || stored;
    }

    public boolean enabled(UUID user, NotificationType type, Channel channel) {
        if (type.mandatory()) return true;
        Boolean stored = jdbc.query("""
                SELECT enabled FROM notification_preferences
                WHERE user_id = :user AND notification_type = :type AND channel = :channel
                """, new MapSqlParameterSource("user", user).addValue("type", type.name())
                .addValue("channel", channel.name()), (row, number) -> row.getBoolean(1)).stream().findFirst().orElse(null);
        return enabled(type, stored);
    }

    /** The effective choice of every type for this user. */
    public Map<NotificationType, Choice> of(UUID user) {
        Map<NotificationType, Map<Channel, Boolean>> stored = new EnumMap<>(NotificationType.class);
        jdbc.query("SELECT notification_type, channel, enabled FROM notification_preferences WHERE user_id = :user",
                Map.of("user", user), row -> {
                    stored.computeIfAbsent(NotificationType.valueOf(row.getString(1)), key -> new EnumMap<>(Channel.class))
                            .put(Channel.valueOf(row.getString(2)), row.getBoolean(3));
                });
        Map<NotificationType, Choice> effective = new EnumMap<>(NotificationType.class);
        for (NotificationType type : NotificationType.values()) {
            Map<Channel, Boolean> choices = stored.getOrDefault(type, Map.of());
            effective.put(type, new Choice(enabled(type, choices.get(Channel.IN_APP)),
                    enabled(type, choices.get(Channel.EMAIL))));
        }
        return effective;
    }

    /** Stores one choice; a mandatory type can only be stored as on (the caller refuses off before this). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void store(UUID user, NotificationType type, Channel channel, boolean enabled, Instant at) {
        Objects.requireNonNull(type, "type");
        if (type.mandatory() && !enabled) throw new IllegalArgumentException("A mandatory notification cannot be turned off");
        jdbc.update("""
                INSERT INTO notification_preferences(user_id, notification_type, channel, enabled, updated_at)
                VALUES (:user, :type, :channel, :enabled, :at)
                ON CONFLICT (user_id, notification_type, channel) DO UPDATE SET enabled = EXCLUDED.enabled,
                    updated_at = EXCLUDED.updated_at
                """, new MapSqlParameterSource("user", user).addValue("type", type.name())
                .addValue("channel", channel.name()).addValue("enabled", enabled).addValue("at", Timestamp.from(at)));
    }
}
