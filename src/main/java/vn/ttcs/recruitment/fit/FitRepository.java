package vn.ttcs.recruitment.fit;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.candidate.CandidateLabels;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Task 413 (story S5-09): the SQL of recorded qualifications and fit results (V65_4). */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
class FitRepository {
    private static final TypeReference<List<CriterionResult>> DETAILS = new TypeReference<>() { };
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;

    FitRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    record StoredFit(UUID positionId, long criteriaVersion, long qualificationsVersion, FitResult result,
                     Instant evaluatedAt) { }

    // The position of an application's requisition; a requisition's position cannot change once it has applications.
    Optional<UUID> positionOf(UUID applicationId) {
        return jdbc.query("SELECT r.position_id FROM job_applications a"
                        + " JOIN recruitment_requisitions r ON r.id = a.requisition_id WHERE a.id = :id",
                new MapSqlParameterSource("id", applicationId), (row, index) -> row.getObject(1, UUID.class))
                .stream().findFirst();
    }

    // Applications whose stored result is missing, names another position, or older criteria or qualifications than
    // the current ones (FitRefreshWorker). No row is locked.
    List<UUID> staleApplications(int limit) {
        return jdbc.queryForList("""
                SELECT a.id FROM job_applications a
                JOIN recruitment_requisitions r ON r.id = a.requisition_id
                LEFT JOIN application_fit_results f ON f.application_id = a.id
                LEFT JOIN position_required_criteria c ON c.position_id = r.position_id
                LEFT JOIN application_qualifications q ON q.application_id = a.id
                WHERE f.application_id IS NULL OR f.position_id <> r.position_id
                   OR f.criteria_version <> COALESCE(c.version, 0) OR f.qualifications_version <> COALESCE(q.version, 0)
                ORDER BY a.id LIMIT :limit
                """, new MapSqlParameterSource("limit", limit), UUID.class);
    }

    Optional<Qualifications> qualifications(UUID applicationId) {
        var parameters = new MapSqlParameterSource("id", applicationId);
        List<CandidateLabels.Label> skills = jdbc.query("SELECT display_name, skill_key FROM application_skills"
                        + " WHERE application_id = :id ORDER BY sort_order", parameters,
                (row, index) -> new CandidateLabels.Label(row.getString("display_name"), row.getString("skill_key")));
        return jdbc.query("""
                SELECT q.experience_years, q.education_level, q.skills_recorded, q.version, q.recorded_at,
                       q.recorded_by, u.full_name AS recorded_by_name
                FROM application_qualifications q JOIN user_accounts u ON u.id = q.recorded_by
                WHERE q.application_id = :id
                """, parameters, (row, index) -> new Qualifications(row.getBigDecimal("experience_years"),
                row.getString("education_level") == null ? null : EducationLevel.valueOf(row.getString("education_level")),
                skills, row.getBoolean("skills_recorded"), row.getLong("version"),
                row.getTimestamp("recorded_at").toInstant(),
                row.getObject("recorded_by", UUID.class), row.getString("recorded_by_name"))).stream().findFirst();
    }

    // The stored version (0 when nothing was recorded yet), the row locked when there is one.
    long lockQualificationsVersion(UUID applicationId) {
        return jdbc.query("SELECT version FROM application_qualifications WHERE application_id = :id FOR UPDATE",
                new MapSqlParameterSource("id", applicationId), (row, index) -> row.getLong(1)).stream().findFirst()
                .orElse(0L);
    }

    void saveQualifications(UUID applicationId, BigDecimal experienceYears, EducationLevel education,
                            List<CandidateLabels.Label> skills, boolean skillsRecorded, UUID actor, Instant now,
                            long version) {
        var parameters = new MapSqlParameterSource("id", applicationId).addValue("years", experienceYears)
                .addValue("skillsRecorded", skillsRecorded)
                .addValue("education", education == null ? null : education.name()).addValue("actor", actor)
                .addValue("now", Timestamp.from(now)).addValue("version", version);
        jdbc.update("""
                INSERT INTO application_qualifications(application_id, experience_years, education_level, skills_recorded,
                    recorded_by, recorded_at, version)
                VALUES (:id, :years, :education, :skillsRecorded, :actor, :now, :version)
                ON CONFLICT (application_id) DO UPDATE SET experience_years = EXCLUDED.experience_years,
                    education_level = EXCLUDED.education_level, skills_recorded = EXCLUDED.skills_recorded,
                    recorded_by = EXCLUDED.recorded_by,
                    recorded_at = EXCLUDED.recorded_at, version = EXCLUDED.version
                """, parameters);
        jdbc.update("DELETE FROM application_skills WHERE application_id = :id", parameters);
        for (int index = 0; index < skills.size(); index++) {
            jdbc.update("INSERT INTO application_skills(application_id, skill_key, display_name, sort_order)"
                    + " VALUES (:id, :key, :display, :order)", new MapSqlParameterSource("id", applicationId)
                    .addValue("key", skills.get(index).key()).addValue("display", skills.get(index).display())
                    .addValue("order", index));
        }
    }

    // Task 414: the recorded qualifications of many applications at once (two statements per chunk of 500 ids), for
    // the ranking's live evaluation of stale results. Applications without qualifications are absent from the map.
    Map<UUID, Qualifications> qualificationsOf(Collection<UUID> applicationIds) {
        Map<UUID, Qualifications> found = new HashMap<>();
        List<UUID> ids = List.copyOf(applicationIds);
        for (int start = 0; start < ids.size(); start += 500) {
            var parameters = new MapSqlParameterSource("ids", ids.subList(start, Math.min(ids.size(), start + 500)));
            Map<UUID, List<CandidateLabels.Label>> skills = new HashMap<>();
            jdbc.query("SELECT application_id, display_name, skill_key FROM application_skills"
                    + " WHERE application_id IN (:ids) ORDER BY application_id, sort_order", parameters, row -> {
                        skills.computeIfAbsent(row.getObject("application_id", UUID.class), id -> new ArrayList<>())
                                .add(new CandidateLabels.Label(row.getString("display_name"), row.getString("skill_key")));
                    });
            jdbc.query("""
                    SELECT q.application_id, q.experience_years, q.education_level, q.skills_recorded, q.version,
                           q.recorded_at, q.recorded_by, u.full_name AS recorded_by_name
                    FROM application_qualifications q JOIN user_accounts u ON u.id = q.recorded_by
                    WHERE q.application_id IN (:ids)
                    """, parameters, row -> {
                        UUID id = row.getObject("application_id", UUID.class);
                        found.put(id, new Qualifications(row.getBigDecimal("experience_years"),
                                row.getString("education_level") == null ? null
                                        : EducationLevel.valueOf(row.getString("education_level")),
                                skills.getOrDefault(id, List.of()), row.getBoolean("skills_recorded"),
                                row.getLong("version"), row.getTimestamp("recorded_at").toInstant(),
                                row.getObject("recorded_by", UUID.class), row.getString("recorded_by_name")));
                    });
        }
        return found;
    }

    // A newer computation never loses to an older one: the row changes only when the versions it was computed from
    // are not older than the stored ones (both versions only grow).
    void storeResult(UUID applicationId, UUID positionId, long criteriaVersion, long qualificationsVersion,
                     FitResult result, Instant now) {
        jdbc.update("""
                INSERT INTO application_fit_results(application_id, position_id, criteria_version, qualifications_version,
                    status, required_met, score, details, evaluated_at)
                VALUES (:id, :position, :criteria, :qualifications, :status, :met, :score, CAST(:details AS jsonb), :now)
                ON CONFLICT (application_id) DO UPDATE SET position_id = EXCLUDED.position_id,
                    criteria_version = EXCLUDED.criteria_version, qualifications_version = EXCLUDED.qualifications_version,
                    status = EXCLUDED.status, required_met = EXCLUDED.required_met, score = EXCLUDED.score,
                    details = EXCLUDED.details, evaluated_at = EXCLUDED.evaluated_at
                WHERE application_fit_results.criteria_version <= EXCLUDED.criteria_version
                  AND application_fit_results.qualifications_version <= EXCLUDED.qualifications_version
                """, new MapSqlParameterSource("id", applicationId).addValue("position", positionId)
                .addValue("criteria", criteriaVersion).addValue("qualifications", qualificationsVersion)
                .addValue("status", result.status().name()).addValue("met", result.requiredMet())
                .addValue("score", result.score()).addValue("details", json.writeValueAsString(result.criteria()))
                .addValue("now", Timestamp.from(now)));
    }

    Optional<StoredFit> result(UUID applicationId) {
        return jdbc.query("SELECT position_id, criteria_version, qualifications_version, status, required_met, score,"
                        + " details::text AS details, evaluated_at FROM application_fit_results WHERE application_id = :id",
                new MapSqlParameterSource("id", applicationId), (row, index) -> new StoredFit(
                        row.getObject("position_id", UUID.class), row.getLong("criteria_version"),
                        row.getLong("qualifications_version"), new FitResult(FitStatus.valueOf(row.getString("status")),
                        row.getBoolean("required_met"), row.getObject("score", Integer.class),
                        json.readValue(row.getString("details"), DETAILS)), row.getTimestamp("evaluated_at").toInstant()))
                .stream().findFirst();
    }
}
