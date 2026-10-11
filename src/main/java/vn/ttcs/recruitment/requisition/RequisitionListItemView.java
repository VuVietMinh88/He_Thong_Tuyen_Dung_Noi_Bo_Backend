package vn.ttcs.recruitment.requisition;

import vn.ttcs.recruitment.common.BusinessCalendar;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

// Task 298 (story S3-08, TRAN DUC LUONG): one row of GET /requisitions: the requisition (15 fields of RequisitionView)
// plus how long it has been open and how long until it is needed, in app.business-zone, against one "today" per page.
// Task 297: overdue only while the requisition is still being staffed (OVERDUE_STATUSES).
public record RequisitionListItemView(UUID id, UUID positionId, UUID departmentId, int headcount,
                                      RequisitionReason reason, Long proposedSalaryMin, Long proposedSalaryMax,
                                      String salaryJustification, LocalDate neededBy, String jobDescription,
                                      String candidateRequirements, RequisitionStatus status, UUID createdBy,
                                      Instant createdAt, Instant updatedAt, long daysOpen,
                                      Long daysUntilNeededBy, boolean overdue) {

    // Task 297 (story S3-08): the statuses in which a requisition is still being staffed, so a passed needed-by date makes
    // it overdue (task 298). RequisitionStatus asks every new status to be classified here; RequisitionOverdueStatusTest
    // does not compile until it is. A rejected requisition is finished: its date may pass without it being stuck.
    static final Set<RequisitionStatus> OVERDUE_STATUSES = EnumSet.of(RequisitionStatus.DRAFT,
            RequisitionStatus.PENDING_APPROVAL, RequisitionStatus.RETURNED, RequisitionStatus.APPROVED);

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
                OVERDUE_STATUSES.contains(view.status()) && daysUntilNeededBy != null && daysUntilNeededBy < 0);
    }
}
