package vn.ttcs.recruitment.fit;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.ttcs.recruitment.candidate.CandidateLabels;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Task 412 (story S5-09): the SQL of the required criteria (V65_3). */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
class RequiredCriteriaRepository {
    private final NamedParameterJdbcTemplate jdbc;

    RequiredCriteriaRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    boolean positionExists(UUID positionId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM positions WHERE id = :id)",
                new MapSqlParameterSource("id", positionId), Boolean.class));
    }

    // The position row FOR NO KEY UPDATE: saves of one position's criteria, and the evaluations that read them
    // (FOR SHARE), happen one after the other. False for an unknown position.
    boolean lockPosition(UUID positionId) {
        return !jdbc.queryForList("SELECT id FROM positions WHERE id = :id FOR NO KEY UPDATE",
                new MapSqlParameterSource("id", positionId), UUID.class).isEmpty();
    }

    Optional<RequiredCriteria> find(UUID positionId) {
        var parameters = new MapSqlParameterSource("id", positionId);
        List<CandidateLabels.Label> skills = jdbc.query("SELECT display_name, skill_key FROM position_required_skills"
                        + " WHERE position_id = :id ORDER BY sort_order", parameters,
                (row, index) -> new CandidateLabels.Label(row.getString("display_name"), row.getString("skill_key")));
        return jdbc.query("""
                SELECT c.min_experience_years, c.required_education, c.version, c.updated_at, c.updated_by,
                       u.full_name AS updated_by_name
                FROM position_required_criteria c JOIN user_accounts u ON u.id = c.updated_by
                WHERE c.position_id = :id
                """, parameters, (row, index) -> new RequiredCriteria(positionId,
                row.getObject("min_experience_years", Integer.class),
                row.getString("required_education") == null ? null : EducationLevel.valueOf(row.getString("required_education")),
                skills, row.getLong("version"), row.getTimestamp("updated_at").toInstant(),
                row.getObject("updated_by", UUID.class), row.getString("updated_by_name"))).stream().findFirst();
    }

    // The stored version (0 when nothing was saved yet), the criteria row locked when there is one.
    long lockVersion(UUID positionId) {
        return jdbc.query("SELECT version FROM position_required_criteria WHERE position_id = :id FOR UPDATE",
                new MapSqlParameterSource("id", positionId), (row, index) -> row.getLong(1)).stream().findFirst()
                .orElse(0L);
    }

    void save(UUID positionId, Integer minExperienceYears, EducationLevel education, List<CandidateLabels.Label> skills,
              UUID actor, Instant now, long version) {
        var parameters = new MapSqlParameterSource("id", positionId).addValue("years", minExperienceYears)
                .addValue("education", education == null ? null : education.name()).addValue("actor", actor)
                .addValue("now", Timestamp.from(now)).addValue("version", version);
        jdbc.update("""
                INSERT INTO position_required_criteria(position_id, min_experience_years, required_education, updated_by,
                    updated_at, version)
                VALUES (:id, :years, :education, :actor, :now, :version)
                ON CONFLICT (position_id) DO UPDATE SET min_experience_years = EXCLUDED.min_experience_years,
                    required_education = EXCLUDED.required_education, updated_by = EXCLUDED.updated_by,
                    updated_at = EXCLUDED.updated_at, version = EXCLUDED.version
                """, parameters);
        jdbc.update("DELETE FROM position_required_skills WHERE position_id = :id", parameters);
        for (int index = 0; index < skills.size(); index++) {
            jdbc.update("INSERT INTO position_required_skills(position_id, skill_key, display_name, sort_order)"
                            + " VALUES (:id, :key, :display, :order)", new MapSqlParameterSource("id", positionId)
                    .addValue("key", skills.get(index).key()).addValue("display", skills.get(index).display())
                    .addValue("order", index));
        }
    }
}
