package vn.ttcs.recruitment.requisition;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Task 285 (story S3-06): the one definition of "the requisitions assigned to a user": those whose team
 * (requisition_recruiters, V16) has the user as the PRIMARY or a SUPPORTING recruiter right now. The change history
 * (V17) never counts: after a handover the new recruiter has the requisition and the old one does not.
 * <p>
 * Used by requisition reads (RequisitionService: GET /requisitions, GET /requisitions/{id} and the reads built on it),
 * by job postings (JOB_POSTINGS_*_SCOPED, task 303) and, once it exists, by the candidate module: a recruiter
 * (CANDIDATES_*_SCOPED) sees the candidates and applications of the requisitions this class says they are assigned to,
 * filtered in SQL with ASSIGNED_REQUISITION_IDS for lists and checked with isAssigned for one record; a write about a
 * candidate checks isAssigned while it holds the requisition row lock, which every change of the team takes first.
 * <p>
 * It never locks and has no isolation of its own: it reads in the caller's transaction (MANDATORY), so a list sees the
 * team of its own snapshot and a write sees the team as it is under its requisition lock. Reads see a handover from
 * the first request that starts after it committed.
 * <p>
 * No role filter on purpose: the primary and the supporting recruiters both count. A later rule that needs one binds
 * RequisitionRecruiterRole.name() (PgJDBC cannot bind a Java enum).
 */
@Component
public class RecruiterAssignmentAccess {
    // The named parameter of ASSIGNED_REQUISITION_IDS: the user's id.
    public static final String ASSIGNEE = "assignee";
    // The ids of the requisitions :assignee is assigned to. A constant fragment: use as "r.id IN (" + ... + ")". The
    // unqualified columns resolve to requisition_recruiters, whatever the aliases of the outer query. Served by
    // requisition_recruiters_recruiter_id_idx (V16).
    public static final String ASSIGNED_REQUISITION_IDS =
            "SELECT requisition_id FROM requisition_recruiters WHERE recruiter_id = :" + ASSIGNEE;

    private final NamedParameterJdbcTemplate jdbc;

    public RecruiterAssignmentAccess(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // Is the user the primary or a supporting recruiter of the requisition now? Built on ASSIGNED_REQUISITION_IDS, so
    // a single record and a list can never answer differently. x IN (no rows) is false, never null.
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean isAssigned(UUID requisitionId, UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT CAST(:requisitionId AS uuid) IN (" + ASSIGNED_REQUISITION_IDS + ")",
                new MapSqlParameterSource("requisitionId", requisitionId).addValue(ASSIGNEE, userId),
                Boolean.class));
    }
}
