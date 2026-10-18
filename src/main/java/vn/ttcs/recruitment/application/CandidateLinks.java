package vn.ttcs.recruitment.application;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Task 360 (story S4-09, "Một hồ sơ ứng viên liên kết nhiều lần ứng tuyển"): links a stored application to the one
 * candidate profile of its normalized email (V32), creating the profile with the person's first application.
 * <p>
 * The email is not verified (the intake's 201 returns the tracking code), so a profile only groups applications; it
 * never decides whose name or phone is right, and every application keeps its own snapshot. Merging by phone or by
 * hand (S5-01) will move candidate_applications rows.
 * <p>
 * MANDATORY: the link belongs to the application's own transaction (ApplicationStorageService.store, READ COMMITTED),
 * so a duplicate, a failed CV insert or a caller's rollback leaves no candidate and no link behind.
 */
@Repository
class CandidateLinks {
    private final NamedParameterJdbcTemplate jdbc;

    CandidateLinks(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    // Call after the application row is inserted (after its duplicate arbiter). Returns the candidate's id.
    @Transactional(propagation = Propagation.MANDATORY)
    UUID link(UUID applicationId, String normalizedEmail, Instant at) {
        var parameters = new MapSqlParameterSource("id", UUID.randomUUID()).addValue("email", normalizedEmail)
                .addValue("application", applicationId).addValue("at", Timestamp.from(at));
        // A concurrent first application of the same email (another requisition) makes this statement wait for its
        // transaction on the unique index; nothing else is locked, so there is no cycle.
        jdbc.update("""
                INSERT INTO candidates (id, normalized_email, created_at) VALUES (:id, :email, :at)
                ON CONFLICT ON CONSTRAINT candidates_normalized_email_key DO NOTHING
                """, parameters);
        // Two statements on purpose: under READ COMMITTED this one takes a new snapshot, so it sees the candidate a
        // concurrent first application committed while the insert above waited; one statement would not see it.
        // Under REPEATABLE READ that conflict ends in a serialization failure instead; no caller of store() uses it.
        var linked = jdbc.queryForList("""
                INSERT INTO candidate_applications (application_id, candidate_id, linked_at)
                SELECT :application, c.id, :at FROM candidates c WHERE c.normalized_email = :email
                RETURNING candidate_id
                """, parameters, UUID.class);
        if (linked.size() != 1) throw new IllegalStateException("The application was not linked to one candidate");
        return linked.getFirst();
    }
}
