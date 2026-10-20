package vn.ttcs.recruitment.reporting;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.transaction.TransactionTimedOutException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/** Timeouts and lock waits become 503 busy answers; every other failure keeps its own answer. */
class ReportingBusyTest {

    @Test
    void timeoutsLockWaitsAndDeadlocksAreBusy() {
        assertThat(ReportingBusy.busy(new UncategorizedSQLException("report", "SELECT 1", new SQLException("x", "57014"))))
                .isTrue();
        assertThat(ReportingBusy.busy(new UncategorizedSQLException("cost", "SELECT 1", new SQLException("x", "55P03"))))
                .isTrue();
        assertThat(ReportingBusy.busy(new RuntimeException(new SQLException("x", "40P01")))).isTrue();
        assertThat(ReportingBusy.busy(new TransactionTimedOutException("deadline"))).isTrue();
        assertThat(ReportingBusy.busy(new QueryTimeoutException("timeout"))).isTrue();
        assertThat(ReportingBusy.busy(new DataIntegrityViolationException("x", new SQLException("x", "23505")))).isFalse();
        assertThat(ReportingBusy.busy(new IllegalStateException("bug"))).isFalse();
        var busy = ReportingBusy.reportBusy();
        assertThat(busy.getStatus().value()).isEqualTo(503);
        assertThat(busy.getCode()).isEqualTo("REPORT_BUSY");
    }
}
