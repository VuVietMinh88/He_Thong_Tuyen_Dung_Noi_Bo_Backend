package vn.ttcs.recruitment.fit;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.application.ApplicationStage;
import vn.ttcs.recruitment.candidate.CandidateAccess;
import vn.ttcs.recruitment.candidate.CandidateReader;
import vn.ttcs.recruitment.candidatesearch.CandidateSearchRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Task 414 (story S5-09): the SQL of the ranking suggestion: the applications of one requisition in the asked stages,
 * inside CandidateAccess.READABLE, each with its stored fit result (if any) and the versions needed to tell whether
 * that result is current. The ordering is done by FitRankingService, which evaluates stale rows live.
 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
class FitRankingRepository {
    private static final TypeReference<List<CriterionResult>> DETAILS = new TypeReference<>() { };
    private final NamedParameterJdbcTemplate jdbc;
    private final CandidateAccess access;
    private final ObjectMapper json;

    FitRankingRepository(NamedParameterJdbcTemplate jdbc, CandidateAccess access, ObjectMapper json) {
        this.jdbc = jdbc;
        this.access = access;
        this.json = json;
    }

    record Opening(UUID positionId, boolean readable) { }

    // One application with its stored result (null when none) and its current qualifications version (0 when none).
    record Row(UUID applicationId, UUID candidateId, String fullName, ApplicationStage stage, Instant receivedAt,
               FitRepository.StoredFit stored, long qualificationsVersion) {
        @Override public String toString() { return "Row[" + applicationId + ", personal data redacted]"; }
    }

    // Empty for an unknown requisition (404); readable false outside the reader's scope (403).
    Optional<Opening> opening(CandidateReader reader, UUID requisitionId) {
        return jdbc.query("SELECT r.position_id, " + CandidateSearchRepository.READABLE_OPENING + " AS readable"
                        + " FROM recruitment_requisitions r WHERE r.id = :id",
                access.parameters(reader).addValue("id", requisitionId),
                (row, index) -> new Opening(row.getObject("position_id", UUID.class), row.getBoolean("readable")))
                .stream().findFirst();
    }

    List<Row> rows(CandidateReader reader, UUID requisitionId, List<String> stages) {
        MapSqlParameterSource parameters = access.parameters(reader).addValue("requisition", requisitionId)
                .addValue("stages", stages);
        return jdbc.query("""
                SELECT a.id, ca.candidate_id, a.full_name, a.stage, a.received_at, f.position_id AS fit_position,
                       f.criteria_version, f.qualifications_version AS fit_qualifications_version, f.status,
                       f.required_met, f.score, f.details::text AS details, f.evaluated_at,
                       COALESCE(q.version, 0) AS qualifications_version
                FROM job_applications a
                JOIN recruitment_requisitions r ON r.id = a.requisition_id
                JOIN candidate_applications ca ON ca.application_id = a.id
                LEFT JOIN application_fit_results f ON f.application_id = a.id
                LEFT JOIN application_qualifications q ON q.application_id = a.id
                WHERE a.requisition_id = :requisition AND a.stage IN (:stages) AND
                """ + CandidateAccess.READABLE, parameters, (row, index) -> {
                    String status = row.getString("status");
                    FitRepository.StoredFit stored = status == null ? null : new FitRepository.StoredFit(
                            row.getObject("fit_position", UUID.class), row.getLong("criteria_version"),
                            row.getLong("fit_qualifications_version"), new FitResult(FitStatus.valueOf(status),
                            row.getBoolean("required_met"), row.getObject("score", Integer.class),
                            json.readValue(row.getString("details"), DETAILS)),
                            row.getTimestamp("evaluated_at").toInstant());
                    return new Row(row.getObject("id", UUID.class), row.getObject("candidate_id", UUID.class),
                            row.getString("full_name"), ApplicationStage.valueOf(row.getString("stage")),
                            row.getTimestamp("received_at").toInstant(), stored, row.getLong("qualifications_version"));
                });
    }
}
