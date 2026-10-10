package vn.ttcs.recruitment.requisition;

import vn.ttcs.recruitment.common.BusinessCalendar;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

public record RequisitionListItemView(UUID id, UUID positionId, UUID departmentId, int headcount,
                                      RequisitionReason reason, Long proposedSalaryMin, Long proposedSalaryMax,
                                      String salaryJustification, LocalDate neededBy, String jobDescription,
                                      String candidateRequirements, RequisitionStatus status, UUID createdBy,
                                      Instant createdAt, Instant updatedAt, long daysOpen,
                                      Long daysUntilNeededBy, boolean overdue) {

    static RequisitionListItemView from(RecruitmentRequisition requisition, BusinessCalendar calendar,
                                        LocalDate today) {
        RequisitionView view = RequisitionView.from(requisition);
        long daysOpen = Math.max(0, ChronoUnit.DAYS.between(calendar.dateOf(view.createdAt()), today));
        Long daysUntilNeededBy = view.neededBy() == null ? null
                : ChronoUnit.DAYS.between(today, view.neededBy());
        return new RequisitionListItemView(view.id(), view.positionId(), view.departmentId(), view.headcount(),
                view.reason(), view.proposedSalaryMin(), view.proposedSalaryMax(), view.salaryJustification(),
                view.neededBy(), view.jobDescription(), view.candidateRequirements(), view.status(), view.createdBy(),
                view.createdAt(), view.updatedAt(), daysOpen, daysUntilNeededBy,
                daysUntilNeededBy != null && daysUntilNeededBy < 0);
    }
}
