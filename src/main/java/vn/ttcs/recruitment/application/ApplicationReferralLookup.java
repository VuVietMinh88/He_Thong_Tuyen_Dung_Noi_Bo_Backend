package vn.ttcs.recruitment.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * Task 351 (story S4-07): whether a confirmation belongs to a referred application (V36), asked by the confirmation
 * worker at send time, so the mail of a referred candidate says that a staff member submitted it on their behalf. The
 * payload, its encryption and V28 stay as they are. Its own short read-only transaction (statement_timeout 3 s), never
 * the worker's: a failure leaves the confirmation PROCESSING until its lease expires, then it is retried.
 */
@Component
class ApplicationReferralLookup {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    ApplicationReferralLookup(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setReadOnly(true);
        transaction.setTimeout(5);
    }

    boolean isReferral(UUID applicationId) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            jdbc.execute("SET LOCAL statement_timeout = '3s'");
            return jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM application_referrals WHERE application_id = ?)",
                    Boolean.class, applicationId);
        }));
    }
}
