package vn.ttcs.recruitment.auth.passwordreset;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Repository
public class PasswordResetDeviceRepository {
    private final JdbcTemplate jdbc;

    public PasswordResetDeviceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Creates the row of a new browser and locks it until the transaction ends, so parallel requests of one browser
     * are counted one after the other. Must be called inside a transaction.
     */
    public PasswordResetDevice lock(UUID id, Instant now) {
        jdbc.update("""
                INSERT INTO password_reset_devices (id, updated_at) VALUES (?, ?)
                ON CONFLICT (id) DO NOTHING
                """, id, Timestamp.from(now));
        return jdbc.queryForObject("""
                SELECT id, request_count, window_started_at, locked_until
                FROM password_reset_devices WHERE id = ? FOR UPDATE
                """, (row, number) -> new PasswordResetDevice(row.getObject("id", UUID.class),
                row.getInt("request_count"), instant(row.getTimestamp("window_started_at")),
                instant(row.getTimestamp("locked_until"))), id);
    }

    public void save(PasswordResetDevice device, Instant now) {
        jdbc.update("""
                UPDATE password_reset_devices
                SET request_count = ?, window_started_at = ?, locked_until = ?, updated_at = ?
                WHERE id = ?
                """, device.requestCount(), timestamp(device.windowStartedAt()), timestamp(device.lockedUntil()),
                Timestamp.from(now), device.id());
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
