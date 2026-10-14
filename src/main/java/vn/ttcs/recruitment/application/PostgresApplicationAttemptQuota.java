package vn.ttcs.recruitment.application;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.common.ApiException;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

@Component
public class PostgresApplicationAttemptQuota implements ApplicationAttemptQuota {
    private final ApplicationClientIpResolver addresses;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public PostgresApplicationAttemptQuota(ApplicationClientIpResolver addresses, JdbcTemplate jdbc,
            PlatformTransactionManager manager) {
        this.addresses = addresses; this.jdbc = jdbc;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(5);
    }

    @Override public void checkAndRecord(HttpServletRequest request) {
        String ip = addresses.resolve(request);
        long retry;
        try { retry = Objects.requireNonNull(transaction.execute(status -> record(ip))); }
        catch (RuntimeException exception) { throw unavailable(); }
        if (retry > 0) throw new ApplicationRateLimitException(retry);
    }

    private long record(String ip) {
        jdbc.execute("SET LOCAL lock_timeout = '2s'");
        jdbc.execute("SET LOCAL statement_timeout = '3s'");
        boolean locked = false;
        // Cleanup may delete an idle row between INSERT DO NOTHING and SELECT FOR UPDATE.
        for (int attempt = 0; attempt < 3 && !locked; attempt++) {
            jdbc.update("""
                    INSERT INTO application_ip_quotas(client_ip,last_attempt_at) VALUES (CAST(? AS inet),clock_timestamp())
                    ON CONFLICT (client_ip) DO NOTHING
                    """, ip);
            locked = !jdbc.query("SELECT client_ip FROM application_ip_quotas WHERE client_ip=CAST(? AS inet) FOR UPDATE",
                    (row, number) -> true, ip).isEmpty();
        }
        if (!locked) throw new IllegalStateException("Quota row unavailable");
        // Read after the lock, not at transaction start: a waiting request must use its actual admission time.
        Instant now = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
        Instant cutoff = now.minusSeconds(3600);
        jdbc.update("DELETE FROM application_ip_attempts WHERE client_ip=CAST(? AS inet) AND attempted_at<=?",
                ip, Timestamp.from(cutoff));
        var live = jdbc.query("""
                SELECT attempted_at FROM application_ip_attempts WHERE client_ip=CAST(? AS inet) ORDER BY attempted_at
                """, (row, number) -> row.getTimestamp(1).toInstant(), ip);
        if (live.size() >= 5) {
            // If external data corruption added more than five, wait until enough entries expire.
            Duration wait = Duration.between(now, live.get(live.size() - 5).plusSeconds(3600));
            return Math.max(1, wait.getSeconds() + (wait.getNano() > 0 ? 1 : 0));
        }
        jdbc.update("INSERT INTO application_ip_attempts(client_ip,attempted_at) VALUES (CAST(? AS inet),?)",
                ip, Timestamp.from(now));
        jdbc.update("UPDATE application_ip_quotas SET last_attempt_at=? WHERE client_ip=CAST(? AS inet)",
                Timestamp.from(now), ip);
        return 0;
    }

    public int deleteExpired() {
        return Objects.requireNonNull(transaction.execute(status -> {
            jdbc.execute("SET LOCAL lock_timeout = '2s'");
            jdbc.execute("SET LOCAL statement_timeout = '3s'");
            return jdbc.update("""
                    WITH expired AS (
                        SELECT client_ip FROM application_ip_quotas
                        WHERE last_attempt_at<=clock_timestamp()-INTERVAL '1 hour'
                        ORDER BY last_attempt_at LIMIT 500 FOR UPDATE SKIP LOCKED
                    )
                    DELETE FROM application_ip_quotas q USING expired e WHERE q.client_ip=e.client_ip
                    """);
        }));
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "APPLICATION_QUOTA_UNAVAILABLE",
                "Chưa thể kiểm tra lượt gửi hồ sơ. Vui lòng thử lại sau.");
    }
}
