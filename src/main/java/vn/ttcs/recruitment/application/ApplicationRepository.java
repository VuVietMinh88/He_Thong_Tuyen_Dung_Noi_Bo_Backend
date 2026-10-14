package vn.ttcs.recruitment.application;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.UUID;

@Repository
class ApplicationRepository {
    private final NamedParameterJdbcTemplate jdbc;

    ApplicationRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    // Must be called in the storage service transaction, after its requisition and posting locks.
    void insert(StoredApplication application, ApplicationSubmission submission, String title, byte[] trackingHash) {
        int inserted = jdbc.update("""
                INSERT INTO job_applications (id, job_posting_id, requisition_id, full_name, email, normalized_email,
                    phone, source_id, cover_letter, stage, received_at, posting_title, tracking_code_hash)
                VALUES (:id, :posting, :requisition, :name, :email, :normalizedEmail, :phone, :source, :letter,
                    'NEW', :receivedAt, :title, :trackingHash)
                ON CONFLICT ON CONSTRAINT job_applications_requisition_email_key DO NOTHING
                """, new MapSqlParameterSource("id", application.id()).addValue("posting", application.jobPostingId())
                .addValue("requisition", application.requisitionId()).addValue("name", submission.fullName())
                .addValue("email", submission.email()).addValue("normalizedEmail", submission.normalizedEmail())
                .addValue("phone", submission.phone()).addValue("source", submission.sourceId())
                .addValue("letter", submission.coverLetter()).addValue("receivedAt", Timestamp.from(application.receivedAt()))
                .addValue("title", title).addValue("trackingHash", trackingHash));
        // Only the email/requisition arbiter is handled. A tracking hash collision or another database
        // error must still fail normally. No precheck can replace this atomic insert under concurrency.
        if (inserted == 0) throw new DuplicateApplicationException();
    }
}
