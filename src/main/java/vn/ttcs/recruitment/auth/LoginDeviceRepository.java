package vn.ttcs.recruitment.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Repository
public class LoginDeviceRepository {
    private final JdbcTemplate jdbc;

    public LoginDeviceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Must be called inside the login transaction, before taking the account lock. */
    public LoginDevice lock(UUID id, Instant now) {
        jdbc.update("""
                INSERT INTO login_devices (id, updated_at) VALUES (?, ?)
                ON CONFLICT (id) DO NOTHING
                """, id, Timestamp.from(now));
        return jdbc.queryForObject("""
                SELECT id, failed_attempts, locked_until FROM login_devices WHERE id = ? FOR UPDATE
                """, (row, number) -> {
            Timestamp lockedUntil = row.getTimestamp("locked_until");
            return new LoginDevice(row.getObject("id", UUID.class), row.getInt("failed_attempts"),
                    lockedUntil == null ? null : lockedUntil.toInstant());
        }, id);
    }

    public void save(LoginDevice device, Instant now) {
        jdbc.update("""
                UPDATE login_devices SET failed_attempts = ?, locked_until = ?, updated_at = ? WHERE id = ?
                """, device.failedAttempts(),
                device.lockedUntil() == null ? null : Timestamp.from(device.lockedUntil()),
                Timestamp.from(now), device.id());
    }
}
