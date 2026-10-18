package vn.ttcs.recruitment.jobposting;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Task 308: the review and publication history of job postings (V22 job_posting_events). This class only adds rows:
 * no event is ever changed or removed. Every writer holds the requisition and posting row locks and writes the event in
 * the same transaction as the status change, with the posting's revision after that change, so two steps can never
 * take the same revision; one that skipped the locks would fail on job_posting_events_revision_key.
 */
@Repository
class JobPostingEventRepository {
    private final NamedParameterJdbcTemplate jdbc;

    JobPostingEventRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // revision: the posting's revision right after this step. The enums are bound by name: PgJDBC cannot bind a Java
    // enum. comment: null when there is none (V22 requires it for REVISION_REQUESTED and refuses it for PUBLISHED).
    void append(UUID postingId, int revision, JobPostingEventType type, JobPostingStatus before,
                JobPostingStatus after, UUID actorId, Instant at, String comment) {
        jdbc.update("""
                INSERT INTO job_posting_events (id, job_posting_id, revision, event_type, status_before, status_after,
                    actor_id, occurred_at, comment)
                VALUES (:id, :posting, :revision, :type, :before, :after, :actor, :at, :comment)
                """, new MapSqlParameterSource("id", UUID.randomUUID())
                .addValue("posting", postingId)
                .addValue("revision", revision)
                .addValue("type", type.name())
                .addValue("before", before.name())
                .addValue("after", after.name())
                .addValue("actor", actorId)
                // OffsetDateTime in UTC is sent as TIMESTAMPTZ, independent of the session or JVM time zone.
                .addValue("at", at.atOffset(ZoneOffset.UTC))
                .addValue("comment", comment));
    }
}
