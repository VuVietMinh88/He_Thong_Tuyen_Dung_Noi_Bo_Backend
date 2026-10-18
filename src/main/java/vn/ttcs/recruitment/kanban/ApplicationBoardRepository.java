package vn.ttcs.recruitment.kanban;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.ttcs.recruitment.application.ApplicationStage;
import vn.ttcs.recruitment.candidate.CandidateAccess;
import vn.ttcs.recruitment.candidate.CandidateReader;
import vn.ttcs.recruitment.department.DepartmentRepository;
import vn.ttcs.recruitment.requisition.RecruiterAssignmentAccess;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
class ApplicationBoardRepository {
    // Opening discovery must work even before the first application exists. Same assignment/department rules as
    // CandidateAccess.READABLE, using r.id because there is no application alias in this query.
    static final String READABLE_OPENING = "(CAST(:allCandidates AS boolean) OR r.id IN ("
            + RecruiterAssignmentAccess.ASSIGNED_REQUISITION_IDS + ") OR r.department_id IN ("
            + DepartmentRepository.MANAGED_DEPARTMENT_IDS + "))";
    private static final String FILTERED = """
            FROM job_applications a
            JOIN recruitment_requisitions r ON r.id = a.requisition_id
            JOIN candidate_applications ca ON ca.application_id = a.id
            WHERE a.stage <> 'WITHDRAWN'
              AND (CAST(:position AS uuid) IS NULL OR r.position_id = :position)
              AND (CAST(:requisition AS uuid) IS NULL OR r.id = :requisition)
            """ + " AND " + CandidateAccess.READABLE
            + " AND (NOT CAST(:assignedOnly AS boolean) OR r.id IN ("
            + RecruiterAssignmentAccess.ASSIGNED_REQUISITION_IDS + "))";

    private final NamedParameterJdbcTemplate jdbc;
    private final CandidateAccess access;

    ApplicationBoardRepository(NamedParameterJdbcTemplate jdbc, CandidateAccess access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    Optional<Boolean> readableOpening(CandidateReader reader, UUID requisition) {
        return jdbc.query("SELECT " + READABLE_OPENING + " AS readable FROM recruitment_requisitions r WHERE r.id = :id",
                access.parameters(reader).addValue("id", requisition), (row, index) -> row.getBoolean("readable"))
                .stream().findFirst();
    }

    boolean positionExists(UUID position) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM positions WHERE id = :id)",
                new MapSqlParameterSource("id", position), Boolean.class));
    }

    boolean hasReadablePosition(CandidateReader reader, UUID position) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM recruitment_requisitions r"
                        + " WHERE r.position_id = :position AND " + READABLE_OPENING + ")",
                access.parameters(reader).addValue("position", position), Boolean.class));
    }

    Map<ApplicationStage, Long> totals(CandidateReader reader, ApplicationBoardQuery query, boolean assignedOnly) {
        Map<ApplicationStage, Long> result = new EnumMap<>(ApplicationStage.class);
        jdbc.query("SELECT a.stage, count(*) AS total " + FILTERED + " GROUP BY a.stage",
                parameters(reader, query, assignedOnly), row -> {
                    result.put(ApplicationStage.valueOf(row.getString("stage")), row.getLong("total"));
                });
        return result;
    }

    List<ApplicationBoardView.Card> cards(CandidateReader reader, ApplicationBoardQuery query, boolean assignedOnly) {
        String sql = """
                SELECT * FROM (
                    SELECT a.id, ca.candidate_id, a.requisition_id, r.position_id, a.full_name,
                           a.posting_title, a.received_at, a.stage,
                           row_number() OVER (PARTITION BY a.stage ORDER BY a.received_at DESC, a.id DESC) AS row_number
                """ + FILTERED + ") ranked WHERE row_number > :offset AND row_number <= :end"
                + " ORDER BY stage, row_number";
        return jdbc.query(sql, parameters(reader, query, assignedOnly)
                        .addValue("offset", query.offset()).addValue("end", query.offset() + query.size()),
                (row, index) -> new ApplicationBoardView.Card(row.getObject("id", UUID.class),
                        row.getObject("candidate_id", UUID.class), row.getObject("requisition_id", UUID.class),
                        row.getObject("position_id", UUID.class), row.getString("full_name"),
                        row.getString("posting_title"), row.getTimestamp("received_at").toInstant(),
                        ApplicationStage.valueOf(row.getString("stage"))));
    }

    private MapSqlParameterSource parameters(CandidateReader reader, ApplicationBoardQuery query, boolean assignedOnly) {
        return access.parameters(reader).addValue("position", query.positionId())
                .addValue("requisition", query.requisitionId()).addValue("assignedOnly", assignedOnly);
    }
}
