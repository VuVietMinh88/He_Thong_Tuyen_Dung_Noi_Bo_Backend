package vn.ttcs.recruitment.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.Optional;

/**
 * Task 339: the lookup's only read. One statement checks the hash, the normalized email and that the code was not
 * revoked together, so an unknown code, a wrong email and a revoked code take the same path: one probe of the UNIQUE
 * index job_applications_tracking_hash_key, no Java branch between them.
 *
 * <p>Task 341 (story S4-05): the only read behind {@link PublicApplicationView}. It reads a fixed whitelist of
 * job_applications columns, posting_title, received_at and stage (tracking_code_hash, normalized_email and
 * tracking_revoked_at only to match the row), and joins no other table: no staff account, requisition, recruiter
 * team, department, posting, catalog, CV or outbox, and none of the notes, evaluations, interviews or interviewers
 * that Sprint 5 and 6 add. The stage leaves this class only through {@link PublicApplicationView#of}, that is through
 * {@link PublicApplicationStatus#of}. PublicApplicationPrivacyIntegrationTest answers a lookup while every other
 * table of the schema is locked exclusively, so a join to any of them, present or future, fails it.
 */
@Repository
class PublicApplicationReader {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    PublicApplicationReader(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        transaction = new TransactionTemplate(manager);
        transaction.setReadOnly(true);
    }

    Optional<PublicApplicationView> find(TrackingCredential credential) {
        return Objects.requireNonNull(transaction.execute(status -> {
            // A lookup never waits longer than this, whatever holds job_applications.
            jdbc.execute("SET LOCAL statement_timeout = '3s'");
            return jdbc.query("""
                    SELECT posting_title, received_at, stage FROM job_applications
                    WHERE tracking_code_hash = ? AND normalized_email = ? AND tracking_revoked_at IS NULL
                    """, (row, number) -> PublicApplicationView.of(row.getString(1),
                            row.getTimestamp(2).toInstant(), ApplicationStage.valueOf(row.getString(3))),
                    credential.hash(), credential.normalizedEmail()).stream().findFirst();
        }));
    }
}
