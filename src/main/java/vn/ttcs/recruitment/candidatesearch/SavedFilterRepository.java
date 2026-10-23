package vn.ttcs.recruitment.candidatesearch;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.candidate.CandidateText;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Task 394: every statement names the owner, so a filter id alone never reaches another account's row. */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
class SavedFilterRepository {
    private static final String COLUMNS = "SELECT id, name, criteria::text AS criteria, created_at, updated_at, version"
            + " FROM candidate_saved_filters";
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;

    SavedFilterRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    List<SavedFilterView> list(UUID owner) {
        return jdbc.query(COLUMNS + " WHERE owner_id = :owner ORDER BY normalized_name, id",
                new MapSqlParameterSource("owner", owner), (row, index) -> view(row));
    }

    Optional<SavedFilterView> find(UUID owner, UUID id) {
        return jdbc.query(COLUMNS + " WHERE id = :id AND owner_id = :owner",
                new MapSqlParameterSource("id", id).addValue("owner", owner), (row, index) -> view(row))
                .stream().findFirst();
    }

    Optional<SavedFilterView> lock(UUID owner, UUID id) {
        return jdbc.query(COLUMNS + " WHERE id = :id AND owner_id = :owner FOR UPDATE",
                new MapSqlParameterSource("id", id).addValue("owner", owner), (row, index) -> view(row))
                .stream().findFirst();
    }

    long countOwned(UUID owner) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM candidate_saved_filters WHERE owner_id = :owner",
                new MapSqlParameterSource("owner", owner), Long.class);
        return count == null ? 0 : count;
    }

    boolean nameTaken(UUID owner, String name, UUID exceptId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM candidate_saved_filters WHERE owner_id = :owner AND normalized_name = :name
                    AND (CAST(:except AS uuid) IS NULL OR id <> :except))
                """, new MapSqlParameterSource("owner", owner).addValue("name", normalize(name))
                .addValue("except", exceptId), Boolean.class));
    }

    void insert(UUID id, UUID owner, String name, CandidateSearchCriteria criteria, Instant now) {
        jdbc.update("""
                INSERT INTO candidate_saved_filters(id, owner_id, name, normalized_name, criteria, created_at, updated_at)
                VALUES (:id, :owner, :name, :normalized, CAST(:criteria AS jsonb), :now, :now)
                """, parameters(id, owner, name, criteria, now));
    }

    boolean update(UUID id, UUID owner, String name, CandidateSearchCriteria criteria, long expectedVersion,
                   Instant now) {
        return jdbc.update("""
                UPDATE candidate_saved_filters SET name = :name, normalized_name = :normalized,
                    criteria = CAST(:criteria AS jsonb), updated_at = GREATEST(:now, created_at), version = version + 1
                WHERE id = :id AND owner_id = :owner AND version = :version
                """, parameters(id, owner, name, criteria, now).addValue("version", expectedVersion)) == 1;
    }

    boolean delete(UUID id, UUID owner) {
        return jdbc.update("DELETE FROM candidate_saved_filters WHERE id = :id AND owner_id = :owner",
                new MapSqlParameterSource("id", id).addValue("owner", owner)) == 1;
    }

    // The name as stored: invisible characters (no-break and zero-width spaces included) trimmed at the ends, every
    // inner run of spaces as one space.
    static String display(String name) {
        String trimmed = CandidateText.trim(name);
        return trimmed == null ? "" : CandidateText.collapseSpaces(trimmed);
    }

    // The key that makes two names the same name for one owner: the stored form in NFC and lower case.
    static String normalize(String name) {
        return Normalizer.normalize(display(name), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    private MapSqlParameterSource parameters(UUID id, UUID owner, String name, CandidateSearchCriteria criteria,
                                             Instant now) {
        return new MapSqlParameterSource("id", id).addValue("owner", owner).addValue("name", display(name))
                .addValue("normalized", normalize(name)).addValue("criteria", json.writeValueAsString(criteria))
                .addValue("now", Timestamp.from(now));
    }

    private SavedFilterView view(ResultSet row) throws SQLException {
        return new SavedFilterView(row.getObject("id", UUID.class), row.getString("name"),
                json.readValue(row.getString("criteria"), CandidateSearchCriteria.class),
                row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant(),
                row.getLong("version"));
    }
}
