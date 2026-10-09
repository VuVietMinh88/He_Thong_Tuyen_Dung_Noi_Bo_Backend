package vn.ttcs.recruitment.requisition;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

// A requisition as the API returns it. Fields the manager has not filled in yet are JSON null.
// proposedSalaryMin/proposedSalaryMax are the manager's own proposal, not the position's standard salary band,
// which stays hidden from callers without SALARY_RANGES_READ_ALL.
public record RequisitionView(UUID id, UUID positionId, UUID departmentId, int headcount, RequisitionReason reason,
                              Long proposedSalaryMin, Long proposedSalaryMax, String salaryJustification,
                              LocalDate neededBy, String jobDescription, String candidateRequirements,
                              RequisitionStatus status, UUID createdBy, Instant createdAt, Instant updatedAt) {

    static RequisitionView from(RecruitmentRequisition requisition) {
        return new RequisitionView(requisition.getId(), requisition.getPositionId(), requisition.getDepartmentId(),
                requisition.getHeadcount(), requisition.getReason(), requisition.getProposedSalaryMin(),
                requisition.getProposedSalaryMax(), requisition.getSalaryJustification(), requisition.getNeededBy(),
                requisition.getJobDescription(), requisition.getCandidateRequirements(), requisition.getStatus(),
                requisition.getCreatedBy(), requisition.getCreatedAt(), requisition.getUpdatedAt());
    }
}
