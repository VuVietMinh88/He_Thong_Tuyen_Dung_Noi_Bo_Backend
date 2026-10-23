package vn.ttcs.recruitment.messaging;

import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.common.ApiException;

import java.sql.SQLException;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The transactions of the messaging APIs. A write is one REQUIRES_NEW READ COMMITTED transaction of at most 5 s with
 * lock_timeout 2 s and statement_timeout 3 s, like the other write services; a lock or statement timeout or a
 * deadlock rolls everything back and answers 503 with the caller's code, so the request may simply be sent again.
 * A read is one REPEATABLE READ snapshot; it may still write audit rows (candidate access log).
 */
@Component
public class MessagingTransactions {
    // lock_not_available, query_canceled (statement_timeout) and deadlock_detected.
    private static final Set<String> BUSY_STATES = Set.of("55P03", "57014", "40P01");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate writes;
    private final TransactionTemplate reads;

    public MessagingTransactions(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        writes = new TransactionTemplate(manager);
        writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        writes.setTimeout(5);
        reads = new TransactionTemplate(manager);
        reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reads.setTimeout(5);
    }

    public <T> T write(String busyCode, Supplier<T> work) {
        try {
            return writes.execute(status -> {
                jdbc.execute("SET LOCAL lock_timeout = '2s'");
                jdbc.execute("SET LOCAL statement_timeout = '3s'");
                return work.get();
            });
        } catch (RuntimeException failure) {
            if (busy(failure)) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, busyCode,
                        "Dữ liệu đang được xử lý ở một thao tác khác. Vui lòng thử lại sau ít phút.");
            }
            throw failure;
        }
    }

    public <T> T read(Supplier<T> work) {
        return reads.execute(status -> work.get());
    }

    static boolean busy(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof TransientDataAccessException || cause instanceof TransactionTimedOutException
                    || (cause instanceof SQLException sql && BUSY_STATES.contains(sql.getSQLState()))) {
                return true;
            }
        }
        return false;
    }
}
