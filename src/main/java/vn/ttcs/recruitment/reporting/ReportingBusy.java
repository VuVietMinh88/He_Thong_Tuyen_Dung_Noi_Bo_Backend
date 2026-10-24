package vn.ttcs.recruitment.reporting;

import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.TransactionTimedOutException;
import vn.ttcs.recruitment.common.ApiException;

import java.sql.SQLException;
import java.util.Set;

/**
 * A report or a cost write that ran out of time: a statement timeout (57014), a lock wait timeout (55P03), a deadlock
 * (40P01) or the transaction's own timeout. The client gets 503 with a house code and may retry, never a 500, the rule
 * of ApplicationStageChangeService. JdbcTemplate's default translator leaves 55P03 and 57014 uncategorized, so the
 * SQLState is read from the cause chain.
 */
final class ReportingBusy {
    private static final Set<String> BUSY_STATES = Set.of("55P03", "57014", "40P01");

    private ReportingBusy() {
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

    static ApiException reportBusy() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "REPORT_BUSY",
                "Báo cáo đang bận hoặc chạy quá lâu. Vui lòng thử lại sau ít phút.");
    }
}
