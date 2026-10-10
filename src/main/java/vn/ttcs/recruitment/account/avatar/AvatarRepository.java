package vn.ttcs.recruitment.account.avatar;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Reads and writes the V12 table {@code user_avatars}: at most one row per account. */
@Repository
public class AvatarRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public AvatarRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts the account's avatar, or replaces every column of the one already stored. */
    public void save(UUID userId, ProcessedAvatar avatar, Instant updatedAt) {
        var parameters = new MapSqlParameterSource("userId", userId)
                .addValue("contentType", ProcessedAvatar.CONTENT_TYPE)
                .addValue("image", avatar.avatar())
                .addValue("thumbnail", avatar.thumbnail())
                .addValue("sizeBytes", avatar.avatar().length)
                .addValue("updatedAt", Timestamp.from(updatedAt));
        jdbc.update("""
                INSERT INTO user_avatars (user_id, content_type, image, thumbnail, size_bytes, updated_at)
                VALUES (:userId, :contentType, :image, :thumbnail, :sizeBytes, :updatedAt)
                ON CONFLICT (user_id) DO UPDATE SET content_type = EXCLUDED.content_type, image = EXCLUDED.image,
                    thumbnail = EXCLUDED.thumbnail, size_bytes = EXCLUDED.size_bytes, updated_at = EXCLUDED.updated_at
                """, parameters);
    }

    public void delete(UUID userId) {
        jdbc.update("DELETE FROM user_avatars WHERE user_id = :userId", new MapSqlParameterSource("userId", userId));
    }

    /** Reads only the requested picture; the other one stays in the database. */
    public Optional<AvatarFile> find(UUID userId, AvatarSize size) {
        // The column name comes from the enum, never from the request, so building the SQL here is safe.
        String column = switch (size) {
            case FULL -> "image";
            case THUMBNAIL -> "thumbnail";
        };
        return jdbc.query("SELECT content_type, " + column + " AS content FROM user_avatars WHERE user_id = :userId",
                        new MapSqlParameterSource("userId", userId),
                        (row, number) -> new AvatarFile(row.getString("content_type"), row.getBytes("content")))
                .stream().findFirst();
    }

    /** Time of the last upload, or empty when the account has no avatar. Does not read the pictures. */
    public Optional<Instant> findUpdatedAt(UUID userId) {
        return jdbc.query("SELECT updated_at FROM user_avatars WHERE user_id = :userId",
                        new MapSqlParameterSource("userId", userId),
                        (row, number) -> row.getTimestamp("updated_at").toInstant())
                .stream().findFirst();
    }
}
