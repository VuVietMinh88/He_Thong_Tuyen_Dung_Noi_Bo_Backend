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
 * <p>
 * Task 368 (story S5-01): a candidate merged into another one (V68_2, merged_into) keeps its email, and an application
 * with that email joins the candidate it was merged into. One hop is enough, because a merge re-points the candidates
 * already merged into its source. The link locks only that final candidate FOR SHARE, which a merge or an undo of it
 * (FOR UPDATE) makes wait, and then checks that the email still leads there: an application that arrives during a
 * merge joins the target, one that arrives during an undo joins the restored source.
 */
@Repository
class CandidateLinks {
    // More rounds would mean merges and undos of this person committing faster than one application is linked.
    private static final int MAX_ROUNDS = 5;
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
        // Separate statements on purpose: under READ COMMITTED each one takes a new snapshot, so the first sees the
        // candidate a concurrent first application committed while the insert above waited, and the last one sees
        // a merge or an undo that committed while the lock waited. Under REPEATABLE READ those waits end in a
        // serialization failure instead; no caller of store() uses it.
        for (int round = 0; round < MAX_ROUNDS; round++) {
            UUID candidate = jdbc.queryForObject("""
                    SELECT COALESCE(merged_into, id) FROM candidates WHERE normalized_email = :email
                    """, parameters, UUID.class);
            // Once its lock is granted, the candidate must not be merged itself and the email must still lead to it.
            boolean settled = Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT merged_into IS NULL FROM candidates WHERE id = :candidate FOR SHARE
                    """, new MapSqlParameterSource("candidate", candidate), Boolean.class));
            if (!settled || !candidate.equals(jdbc.queryForObject("""
                    SELECT COALESCE(merged_into, id) FROM candidates WHERE normalized_email = :email
                    """, parameters, UUID.class))) {
                continue;
            }
            var linked = jdbc.queryForList("""
                    INSERT INTO candidate_applications (application_id, candidate_id, linked_at)
                    VALUES (:application, :candidate, :at) RETURNING candidate_id
                    """, new MapSqlParameterSource(parameters.getValues()).addValue("candidate", candidate), UUID.class);
            if (linked.size() != 1) throw new IllegalStateException("The application was not linked to one candidate");
            return linked.getFirst();
        }
        throw new IllegalStateException("The candidate of this email kept changing while the application was linked");
    }
}
