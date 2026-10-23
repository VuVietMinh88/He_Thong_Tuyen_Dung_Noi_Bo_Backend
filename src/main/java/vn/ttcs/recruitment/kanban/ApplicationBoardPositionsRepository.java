package vn.ttcs.recruitment.kanban;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.ttcs.recruitment.candidate.CandidateAccess;
import vn.ttcs.recruitment.candidate.CandidateReader;
import vn.ttcs.recruitment.requisition.RecruiterAssignmentAccess;
import vn.ttcs.recruitment.security.AccessScope;

import java.util.List;
import java.util.UUID;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
class ApplicationBoardPositionsRepository {
    private static final String FROM = " FROM recruitment_requisitions r JOIN positions p ON p.id = r.position_id"
            + " JOIN departments d ON d.id = r.department_id WHERE " + ApplicationBoardRepository.READABLE_OPENING;
    private final NamedParameterJdbcTemplate jdbc;
    private final CandidateAccess access;

    ApplicationBoardPositionsRepository(NamedParameterJdbcTemplate jdbc, CandidateAccess access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    long total(CandidateReader reader) {
        Long total = jdbc.queryForObject("SELECT count(*)" + FROM, access.parameters(reader), Long.class);
        return total == null ? 0 : total;
    }

    List<ApplicationBoardPositionsView.Opening> openings(CandidateReader reader, ApplicationBoardQuery page) {
        return jdbc.query("SELECT r.id, r.position_id, p.name AS position_name, r.department_id, d.name AS department_name,"
                        + " r.id IN (" + RecruiterAssignmentAccess.ASSIGNED_REQUISITION_IDS + ") AS assigned" + FROM
                        + " ORDER BY p.name, d.name, r.id LIMIT :size OFFSET :offset",
                access.parameters(reader).addValue("size", page.size()).addValue("offset", page.offset()),
                (row, index) -> new ApplicationBoardPositionsView.Opening(row.getObject("id", UUID.class),
                        row.getObject("position_id", UUID.class), row.getString("position_name"),
                        row.getObject("department_id", UUID.class), row.getString("department_name"),
                        row.getBoolean("assigned"), reader.scope() == AccessScope.ALL || row.getBoolean("assigned")));
    }
}
