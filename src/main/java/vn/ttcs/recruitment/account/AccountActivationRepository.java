package vn.ttcs.recruitment.account;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AccountActivationRepository {
    private final JdbcTemplate jdbc;

    public AccountActivationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID userId, String hash, Instant now, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO account_activation_tokens (user_id,token_hash,created_at,expires_at)
                VALUES (?,?,?,?)
                """, userId, hash, Timestamp.from(now), Timestamp.from(expiresAt));
    }

    public Optional<UUID> findUser(String hash) {
        return jdbc.query("SELECT user_id FROM account_activation_tokens WHERE token_hash = ?",
                (row, index) -> row.getObject("user_id", UUID.class), hash).stream().findFirst();
    }

    public boolean consume(String hash, Instant now) {
        return jdbc.update("""
                UPDATE account_activation_tokens SET consumed_at = ?
                WHERE token_hash = ? AND consumed_at IS NULL AND expires_at > ?
                """, Timestamp.from(now), hash, Timestamp.from(now)) == 1;
    }
}
