package vn.ttcs.recruitment.notification;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Task 543 (story S8-03): the notifications table (V66_6). Every read and write names the recipient, so a query can
 * never return or change another user's notification; read state is per recipient.
 */
@Repository
public class NotificationRepository {
    static final int MAX_TITLE_LENGTH = 200;
    static final int MAX_BODY_LENGTH = 1000;
    private final NamedParameterJdbcTemplate jdbc;

    public NotificationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One notification for one recipient; false when this recipient already has the notification of this key. */
    public record New(UUID recipientId, NotificationType type, String title, String body, NotificationLink link,
                      String dedupeKey, Instant createdAt) {
        public New {
            Objects.requireNonNull(recipientId, "recipientId");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(link, "link");
            Objects.requireNonNull(createdAt, "createdAt");
            title = shorten(title, MAX_TITLE_LENGTH);
            body = shorten(body, MAX_BODY_LENGTH);
            if (title.isBlank() || body.isBlank()) throw new IllegalArgumentException("A notification needs a title and a body");
            if (dedupeKey == null || dedupeKey.isBlank() || dedupeKey.length() > 200) {
                throw new IllegalArgumentException("A notification needs a dedupe key of at most 200 characters");
            }
        }
    }

    public record Stored(UUID id, NotificationType type, String title, String body, NotificationLink link,
                         Instant createdAt, Instant readAt) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean insert(New notification) {
        return jdbc.update("""
                INSERT INTO notifications(id, recipient_id, notification_type, title, body, link_type, link_id, parent_id,
                    dedupe_key, created_at)
                VALUES (:id, :recipient, :type, :title, :body, :linkType, :linkId, :parentId, :dedupe, :at)
                ON CONFLICT (recipient_id, dedupe_key) DO NOTHING
                """, new MapSqlParameterSource("id", UUID.randomUUID())
                .addValue("recipient", notification.recipientId()).addValue("type", notification.type().name())
                .addValue("title", notification.title()).addValue("body", notification.body())
                .addValue("linkType", notification.link().type().name()).addValue("linkId", notification.link().id())
                .addValue("parentId", notification.link().parentId()).addValue("dedupe", notification.dedupeKey())
                .addValue("at", Timestamp.from(notification.createdAt()))) == 1;
    }

    public List<Stored> page(UUID recipient, boolean unreadOnly, int page, int size) {
        return jdbc.query("""
                SELECT id, notification_type, title, body, link_type, link_id, parent_id, created_at, read_at
                FROM notifications WHERE recipient_id = :recipient AND (NOT :unread OR read_at IS NULL)
                ORDER BY created_at DESC, id DESC LIMIT :size OFFSET :offset
                """, new MapSqlParameterSource("recipient", recipient).addValue("unread", unreadOnly)
                .addValue("size", size).addValue("offset", (long) page * size), NotificationRepository::stored);
    }

    public long count(UUID recipient, boolean unreadOnly) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM notifications WHERE recipient_id = :recipient AND (NOT :unread OR read_at IS NULL)
                """, new MapSqlParameterSource("recipient", recipient).addValue("unread", unreadOnly), Long.class);
    }

    public Optional<Stored> find(UUID recipient, UUID id) {
        return jdbc.query("""
                SELECT id, notification_type, title, body, link_type, link_id, parent_id, created_at, read_at
                FROM notifications WHERE recipient_id = :recipient AND id = :id
                """, new MapSqlParameterSource("recipient", recipient).addValue("id", id), NotificationRepository::stored)
                .stream().findFirst();
    }

    /** Marks the recipient's notification read once; a second call keeps the first time. False when not theirs. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markRead(UUID recipient, UUID id, Instant at) {
        return jdbc.update("""
                UPDATE notifications SET read_at = COALESCE(read_at, GREATEST(CAST(:at AS timestamptz), created_at))
                WHERE recipient_id = :recipient AND id = :id
                """, new MapSqlParameterSource("recipient", recipient).addValue("id", id)
                .addValue("at", Timestamp.from(at))) == 1;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int markAllRead(UUID recipient, Instant at) {
        return jdbc.update("""
                UPDATE notifications SET read_at = GREATEST(CAST(:at AS timestamptz), created_at)
                WHERE recipient_id = :recipient AND read_at IS NULL
                """, new MapSqlParameterSource("recipient", recipient).addValue("at", Timestamp.from(at)));
    }

    private static Stored stored(ResultSet row, int number) throws SQLException {
        Timestamp read = row.getTimestamp("read_at");
        return new Stored(row.getObject("id", UUID.class), NotificationType.valueOf(row.getString("notification_type")),
                row.getString("title"), row.getString("body"),
                new NotificationLink(NotificationLink.Type.valueOf(row.getString("link_type")),
                        row.getObject("link_id", UUID.class), row.getObject("parent_id", UUID.class)),
                row.getTimestamp("created_at").toInstant(), read == null ? null : read.toInstant());
    }

    private static String shorten(String text, int max) {
        if (text == null) return "";
        String line = text.replaceAll("[\\p{Cc}\\p{Zl}\\p{Zp}]+", " ").strip();
        if (line.length() <= max) return line;
        int end = max - 1;
        // Never cut an emoji (a surrogate pair) in half.
        if (Character.isHighSurrogate(line.charAt(end - 1))) end--;
        return line.substring(0, end) + "…";
    }
}
