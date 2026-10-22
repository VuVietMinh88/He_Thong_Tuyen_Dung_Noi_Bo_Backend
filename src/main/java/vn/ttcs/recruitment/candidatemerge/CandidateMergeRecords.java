package vn.ttcs.recruitment.candidatemerge;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Task 369 (story S5-01, "Lưu dấu vết gộp và dữ liệu phục vụ hoàn tác"): the merge trail of V68_2. A merge row says
 * who merged which source candidate into which target, when, why and until when it may be undone; its details are the
 * applications it moved, each with the time it had been linked to the source, and the candidates it re-pointed from
 * the source to the target, each with the time of its own merge. That is exactly what an undo needs to put every link
 * back as it was, so nothing else (no JSON snapshot of the profile) is kept.
 * <p>
 * Every method runs in the caller's write or read transaction (MANDATORY): a merge and its trail commit or roll back
 * together, and an undo marks the merge in the transaction that moves the links back. The database keeps the trail
 * append-only (trigger): a merge is only ever changed by its one undo, and its details never.
 */
@Repository
public class CandidateMergeRecords {
    private static final String COLUMNS = """
            SELECT id, target_candidate_id, source_candidate_id, reason, merged_by, merged_at, undo_deadline,
                   undone_by, undone_at
            FROM candidate_merges
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public CandidateMergeRecords(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // One merge as recorded. undoneBy and undoneAt are both null until it is undone. Ids and times only, so toString
    // shows no personal data; the optional reason is staff text and stays out of it.
    public record CandidateMerge(UUID id, UUID targetCandidateId, UUID sourceCandidateId, String reason, UUID mergedBy,
                                 Instant mergedAt, Instant undoDeadline, UUID undoneBy, Instant undoneAt) {
        public CandidateMerge {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(targetCandidateId, "targetCandidateId");
            Objects.requireNonNull(sourceCandidateId, "sourceCandidateId");
            Objects.requireNonNull(mergedBy, "mergedBy");
            Objects.requireNonNull(mergedAt, "mergedAt");
            Objects.requireNonNull(undoDeadline, "undoDeadline");
            if (targetCandidateId.equals(sourceCandidateId)) {
                throw new IllegalArgumentException("A candidate is never merged into itself");
            }
            if ((undoneBy == null) != (undoneAt == null)) {
                throw new IllegalArgumentException("An undo names its actor and its time");
            }
        }

        public boolean undone() { return undoneAt != null; }

        // May it still be undone at this instant? The deadline itself is too late.
        public boolean undoableAt(Instant now) { return !undone() && now.isBefore(undoDeadline); }

        @Override
        public String toString() {
            return "CandidateMerge[" + id + ", " + sourceCandidateId + " into " + targetCandidateId + ", reason redacted]";
        }
    }

    // An application the merge moved, with the time it had been linked to the source (candidate_applications.linked_at).
    public record MovedApplication(UUID applicationId, Instant linkedAt) { }

    // A candidate that had been merged into the source and was re-pointed to the target, with its own merged_at.
    public record Redirect(UUID candidateId, Instant mergedAt) { }

    // The merge and its details, in the caller's transaction. A merge that moved nothing is still a merge (the source
    // may have lost its applications since it was looked at); the details may be empty but never repeat an id.
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(CandidateMerge merge, List<MovedApplication> moved, List<Redirect> redirects) {
        Objects.requireNonNull(merge, "merge");
        if (merge.undone()) {
            throw new IllegalArgumentException("A merge is recorded before it can be undone");
        }
        if (new HashSet<>(moved.stream().map(MovedApplication::applicationId).toList()).size() != moved.size()
                || new HashSet<>(redirects.stream().map(Redirect::candidateId).toList()).size() != redirects.size()) {
            throw new IllegalArgumentException("A merge moves each application and re-points each candidate once");
        }
        jdbc.update("""
                INSERT INTO candidate_merges (id, target_candidate_id, source_candidate_id, reason, merged_by, merged_at,
                    undo_deadline)
                VALUES (:id, :target, :source, :reason, :actor, :at, :deadline)
                """, new MapSqlParameterSource("id", merge.id()).addValue("target", merge.targetCandidateId())
                .addValue("source", merge.sourceCandidateId()).addValue("reason", merge.reason())
                .addValue("actor", merge.mergedBy()).addValue("at", Timestamp.from(merge.mergedAt()))
                .addValue("deadline", Timestamp.from(merge.undoDeadline())));
        jdbc.batchUpdate("""
                INSERT INTO candidate_merge_applications (merge_id, application_id, linked_at)
                VALUES (:merge, :application, :linkedAt)
                """, moved.stream().map(application -> new MapSqlParameterSource("merge", merge.id())
                .addValue("application", application.applicationId())
                .addValue("linkedAt", Timestamp.from(application.linkedAt()))).toArray(MapSqlParameterSource[]::new));
        jdbc.batchUpdate("""
                INSERT INTO candidate_merge_redirects (merge_id, candidate_id, merged_at) VALUES (:merge, :candidate, :at)
                """, redirects.stream().map(redirect -> new MapSqlParameterSource("merge", merge.id())
                .addValue("candidate", redirect.candidateId()).addValue("at", Timestamp.from(redirect.mergedAt())))
                .toArray(MapSqlParameterSource[]::new));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<CandidateMerge> find(UUID mergeId) {
        return jdbc.query(COLUMNS + " WHERE id = :id", new MapSqlParameterSource("id", mergeId), CandidateMergeRecords::merge)
                .stream().findFirst();
    }

    // The merge row FOR UPDATE: two undos of one merge run one after another, and the second sees the first's mark.
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<CandidateMerge> lock(UUID mergeId) {
        return jdbc.query(COLUMNS + " WHERE id = :id FOR UPDATE", new MapSqlParameterSource("id", mergeId),
                CandidateMergeRecords::merge).stream().findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<MovedApplication> movedApplications(UUID mergeId) {
        return jdbc.query("""
                SELECT application_id, linked_at FROM candidate_merge_applications WHERE merge_id = :merge
                ORDER BY application_id
                """, new MapSqlParameterSource("merge", mergeId), (row, number) -> new MovedApplication(
                row.getObject("application_id", UUID.class), row.getTimestamp("linked_at").toInstant()));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<Redirect> redirects(UUID mergeId) {
        return jdbc.query("""
                SELECT candidate_id, merged_at FROM candidate_merge_redirects WHERE merge_id = :merge ORDER BY candidate_id
                """, new MapSqlParameterSource("merge", mergeId), (row, number) -> new Redirect(
                row.getObject("candidate_id", UUID.class), row.getTimestamp("merged_at").toInstant()));
    }

    // The one undo: false when the merge is unknown or already undone (nothing changes then). The database also
    // refuses an undo at or after the deadline (CHECK candidate_merges_undo).
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markUndone(UUID mergeId, UUID actorId, Instant at) {
        return jdbc.update("""
                UPDATE candidate_merges SET undone_by = :actor, undone_at = :at WHERE id = :id AND undone_at IS NULL
                """, new MapSqlParameterSource("id", mergeId).addValue("actor", Objects.requireNonNull(actorId, "actorId"))
                .addValue("at", Timestamp.from(at))) == 1;
    }

    // The merges with this candidate as their target or their source, newest first, at most limit.
    @Transactional(propagation = Propagation.MANDATORY)
    public List<CandidateMerge> involving(UUID candidateId, int limit) {
        return jdbc.query(COLUMNS + """
                 WHERE target_candidate_id = :candidate OR source_candidate_id = :candidate
                ORDER BY merged_at DESC, id DESC LIMIT :limit
                """, new MapSqlParameterSource("candidate", candidateId).addValue("limit", limit),
                CandidateMergeRecords::merge);
    }

    private static CandidateMerge merge(ResultSet row, int number) throws SQLException {
        Timestamp undoneAt = row.getTimestamp("undone_at");
        return new CandidateMerge(row.getObject("id", UUID.class), row.getObject("target_candidate_id", UUID.class),
                row.getObject("source_candidate_id", UUID.class), row.getString("reason"),
                row.getObject("merged_by", UUID.class), row.getTimestamp("merged_at").toInstant(),
                row.getTimestamp("undo_deadline").toInstant(), row.getObject("undone_by", UUID.class),
                undoneAt == null ? null : undoneAt.toInstant());
    }
}
