package vn.ttcs.recruitment.application;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.ttcs.recruitment.candidate.CandidateAccess;
import vn.ttcs.recruitment.candidate.CandidateReader;
import vn.ttcs.recruitment.candidatesearch.CandidateSearchRepository;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.requisition.RecruiterAssignmentAccess;
import vn.ttcs.recruitment.security.AccessScope;
import vn.ttcs.recruitment.security.PermissionModule;

import java.util.Set;
import java.util.UUID;

/**
 * Task 407 (story S5-08): who may enter an application into a requisition by hand: a CANDIDATES read and write
 * permission (from grants read again by the caller), a requisition whose candidates the caller may read
 * (CandidateAccess.READABLE on the opening), and, with a SCOPED write permission, being one of its recruiters now
 * (RecruiterAssignmentAccess), as for every other candidate write. 404 for an unknown requisition, 403 outside the
 * scope. Runs in the caller's transaction.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class ManualApplicationAccess {
    private final NamedParameterJdbcTemplate jdbc;
    private final CandidateAccess access;
    private final RecruiterAssignmentAccess assignments;

    ManualApplicationAccess(NamedParameterJdbcTemplate jdbc, CandidateAccess access,
                            RecruiterAssignmentAccess assignments) {
        this.jdbc = jdbc;
        this.access = access;
        this.assignments = assignments;
    }

    // Returns the reader the disclosure log names.
    CandidateReader requireRequisitionWriter(UUID actorId, Set<String> grants, UUID requisitionId) {
        AccessScope read = AccessScope.read(grants, PermissionModule.CANDIDATES).orDeny();
        AccessScope write = AccessScope.write(grants, PermissionModule.CANDIDATES).orDeny();
        CandidateReader reader = new CandidateReader(actorId, read);
        boolean readable = jdbc.query("SELECT " + CandidateSearchRepository.READABLE_OPENING
                        + " AS readable FROM recruitment_requisitions r WHERE r.id = :id",
                access.parameters(reader).addValue("id", requisitionId), (row, index) -> row.getBoolean("readable"))
                .stream().findFirst().orElseThrow(ManualApplicationAccess::requisitionNotFound);
        if (!readable || write != AccessScope.ALL && !assignments.isAssigned(requisitionId, actorId)) {
            throw new AccessDeniedException("The requisition is outside the caller's candidate write scope");
        }
        return reader;
    }

    static ApiException requisitionNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "REQUISITION_NOT_FOUND", "Không tìm thấy yêu cầu tuyển dụng.");
    }
}
