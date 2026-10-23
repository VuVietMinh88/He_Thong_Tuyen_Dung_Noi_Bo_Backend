package vn.ttcs.recruitment.talentpool;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.ttcs.recruitment.candidate.CandidateAccess;
import vn.ttcs.recruitment.candidate.CandidateLabels;
import vn.ttcs.recruitment.candidate.CandidateReader;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Task 398 (story S5-06): the SQL of the talent pool (V65_5). An entry is visible to a reader exactly when its source
 * application is (CandidateAccess.READABLE on the aliases a and r), so every read applies the live candidate scope.
 * Every entry also carries its candidate's current status (TalentPoolCandidateStatus), read from all of the
 * candidate's applications: a coarse flag, never the other applications themselves.
 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class TalentPoolRepository {
    static final String TITLE = "TITLE";
    static final String SKILL = "SKILL";
    static final String FROM = """
             FROM talent_pool_entries e
             JOIN job_applications a ON a.id = e.source_application_id
             JOIN recruitment_requisitions r ON r.id = a.requisition_id
             JOIN user_accounts adder ON adder.id = e.added_by
             LEFT JOIN user_accounts remover ON remover.id = e.removed_by
            """;
    static final String COLUMNS = """
            SELECT e.id, e.candidate_id, e.source_application_id, a.requisition_id, a.posting_title, a.full_name,
                   a.email, a.phone, e.evidence, e.reason, e.added_at, e.added_by, adder.full_name AS added_by_name,
                   e.removed_at, e.removed_by, remover.full_name AS removed_by_name,
                   ARRAY(SELECT i.display_name FROM talent_pool_interests i WHERE i.entry_id = e.id AND i.kind = 'TITLE'
                         ORDER BY i.sort_order) AS titles,
                   ARRAY(SELECT i.display_name FROM talent_pool_interests i WHERE i.entry_id = e.id AND i.kind = 'SKILL'
                         ORDER BY i.sort_order) AS skills
            """ + "     , " + TalentPoolCandidateStatus.sql() + " AS candidate_status";
    private final NamedParameterJdbcTemplate jdbc;
    private final CandidateAccess access;

    TalentPoolRepository(NamedParameterJdbcTemplate jdbc, CandidateAccess access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    record Visibility(UUID candidateId, UUID sourceApplicationId, boolean readable) { }

    // Empty for an unknown entry; readable false when its source application is outside the reader's scope.
    Optional<Visibility> visibility(CandidateReader reader, UUID entryId) {
        return jdbc.query("SELECT e.candidate_id, e.source_application_id, " + CandidateAccess.READABLE + " AS readable"
                        + " FROM talent_pool_entries e JOIN job_applications a ON a.id = e.source_application_id"
                        + " JOIN recruitment_requisitions r ON r.id = a.requisition_id WHERE e.id = :id",
                access.parameters(reader).addValue("id", entryId), (row, index) -> new Visibility(
                        row.getObject("candidate_id", UUID.class), row.getObject("source_application_id", UUID.class),
                        row.getBoolean("readable"))).stream().findFirst();
    }

    Optional<TalentPoolEntryView> find(UUID entryId) {
        return jdbc.query(COLUMNS + FROM + " WHERE e.id = :id", new MapSqlParameterSource("id", entryId),
                (row, index) -> view(row)).stream().findFirst();
    }

    // The evidence of a final-stage rejection, read from the application's latest move into REJECTED: the REJECT hiring
    // decision made with it, the candidate's decline of an offer, or a rejection straight from INTERVIEW or OFFER.
    // Empty when the latest rejection came earlier in the pipeline or there is none.
    Optional<TalentPoolEvidence> evidence(UUID applicationId) {
        return jdbc.query("""
                SELECT CASE
                         WHEN EXISTS (SELECT 1 FROM hiring_decisions d WHERE d.stage_event_id = e.id AND d.decision = 'REJECT')
                           THEN 'HIRING_DECISION'
                         WHEN e.offer_id IS NOT NULL THEN 'OFFER_DECLINED'
                         WHEN e.stage_before IN ('INTERVIEW', 'OFFER') THEN 'FINAL_STAGE_REJECTION'
                       END AS evidence
                FROM application_stage_events e
                WHERE e.application_id = :id AND e.stage_after = 'REJECTED'
                ORDER BY e.occurred_at DESC, e.id DESC LIMIT 1
                """, new MapSqlParameterSource("id", applicationId), (row, index) -> Optional.ofNullable(
                        row.getString("evidence")).map(TalentPoolEvidence::valueOf))
                .stream().findFirst().flatMap(evidence -> evidence);
    }

    UUID candidateOf(UUID applicationId) {
        return jdbc.queryForObject("SELECT candidate_id FROM candidate_applications WHERE application_id = :id",
                new MapSqlParameterSource("id", applicationId), UUID.class);
    }

    // False when the candidate already has an active entry (the partial unique index decides, also under concurrency).
    boolean insert(UUID entryId, UUID candidateId, UUID applicationId, TalentPoolEvidence evidence, String reason,
                   UUID actor, Instant now) {
        return jdbc.update("""
                INSERT INTO talent_pool_entries(id, candidate_id, source_application_id, evidence, reason, added_by, added_at)
                VALUES (:id, :candidate, :application, :evidence, :reason, :actor, :now)
                ON CONFLICT (candidate_id) WHERE removed_at IS NULL DO NOTHING
                """, new MapSqlParameterSource("id", entryId).addValue("candidate", candidateId)
                .addValue("application", applicationId).addValue("evidence", evidence.name())
                .addValue("reason", reason).addValue("actor", actor).addValue("now", Timestamp.from(now))) == 1;
    }

    void insertInterests(UUID entryId, String kind, List<CandidateLabels.Label> labels) {
        for (int index = 0; index < labels.size(); index++) {
            jdbc.update("INSERT INTO talent_pool_interests(entry_id, kind, interest_key, display_name, sort_order)"
                            + " VALUES (:entry, :kind, :key, :display, :order)",
                    new MapSqlParameterSource(Map.of("entry", entryId, "kind", kind, "key", labels.get(index).key(),
                            "display", labels.get(index).display(), "order", index)));
        }
    }

    // Task 399: the active entries the reader may see, filtered by title of interest and skills, newest first.
    long count(CandidateReader reader, TalentPoolSearch search) {
        Long total = jdbc.queryForObject("SELECT count(*)" + FROM + searchWhere(search), searchParameters(reader, search),
                Long.class);
        return total == null ? 0 : total;
    }

    List<TalentPoolEntryView> page(CandidateReader reader, TalentPoolSearch search) {
        return jdbc.query(COLUMNS + FROM + searchWhere(search) + " ORDER BY e.added_at DESC, e.id DESC"
                        + " LIMIT :limit OFFSET :offset", searchParameters(reader, search)
                .addValue("limit", search.size()).addValue("offset", search.offset()), (row, index) -> view(row));
    }

    private static String searchWhere(TalentPoolSearch search) {
        StringBuilder where = new StringBuilder(" WHERE e.removed_at IS NULL AND ").append(CandidateAccess.READABLE);
        if (search.titleKey() != null) {
            where.append(" AND EXISTS (SELECT 1 FROM talent_pool_interests t WHERE t.entry_id = e.id AND t.kind = 'TITLE'"
                    + " AND t.interest_key LIKE :title ESCAPE '!')");
        }
        if (!search.skillKeys().isEmpty()) {
            where.append(" AND (SELECT count(*) FROM talent_pool_interests s WHERE s.entry_id = e.id AND s.kind = 'SKILL'"
                    + " AND s.interest_key IN (:skills)) = :skillCount");
        }
        return where.toString();
    }

    private MapSqlParameterSource searchParameters(CandidateReader reader, TalentPoolSearch search) {
        MapSqlParameterSource parameters = access.parameters(reader);
        if (search.titleKey() != null) parameters.addValue("title", search.titlePattern());
        if (!search.skillKeys().isEmpty()) {
            parameters.addValue("skills", search.skillKeys()).addValue("skillCount", search.skillKeys().size());
        }
        return parameters;
    }

    // Task 399: the entry's source application (it never changes), read before any lock; empty for an unknown entry.
    Optional<UUID> sourceOf(UUID entryId) {
        return jdbc.query("SELECT source_application_id FROM talent_pool_entries WHERE id = :id",
                new MapSqlParameterSource("id", entryId), (row, index) -> row.getObject(1, UUID.class)).stream().findFirst();
    }

    // The entry row FOR UPDATE, after its requisition and application; true while it is still active.
    boolean lockActive(UUID entryId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT removed_at IS NULL FROM talent_pool_entries WHERE id = :id"
                + " FOR UPDATE", new MapSqlParameterSource("id", entryId), Boolean.class));
    }

    void remove(UUID entryId, UUID actor, Instant now) {
        jdbc.update("UPDATE talent_pool_entries SET removed_by = :actor, removed_at = GREATEST(:now, added_at)"
                        + " WHERE id = :id AND removed_at IS NULL",
                new MapSqlParameterSource("id", entryId).addValue("actor", actor).addValue("now", Timestamp.from(now)));
    }

    static TalentPoolEntryView view(ResultSet row) throws SQLException {
        TalentPoolEvidence evidence = TalentPoolEvidence.valueOf(row.getString("evidence"));
        TalentPoolCandidateStatus status = TalentPoolCandidateStatus.valueOf(row.getString("candidate_status"));
        UUID removedBy = row.getObject("removed_by", UUID.class);
        Timestamp removedAt = row.getTimestamp("removed_at");
        return new TalentPoolEntryView(row.getObject("id", UUID.class), row.getObject("candidate_id", UUID.class),
                row.getObject("source_application_id", UUID.class), row.getObject("requisition_id", UUID.class),
                row.getString("posting_title"), row.getString("full_name"), row.getString("email"),
                row.getString("phone"), evidence, evidence.displayName(), row.getString("reason"),
                strings(row.getArray("titles")), strings(row.getArray("skills")),
                row.getTimestamp("added_at").toInstant(),
                new TalentPoolEntryView.Staff(row.getObject("added_by", UUID.class), row.getString("added_by_name")),
                removedAt == null ? null : removedAt.toInstant(),
                removedBy == null ? null : new TalentPoolEntryView.Staff(removedBy, row.getString("removed_by_name")),
                status, status.displayName());
    }

    private static List<String> strings(java.sql.Array array) throws SQLException {
        if (array == null) return List.of();
        try {
            return new ArrayList<>(List.of((String[]) array.getArray()));
        } finally {
            array.free();
        }
    }
}
