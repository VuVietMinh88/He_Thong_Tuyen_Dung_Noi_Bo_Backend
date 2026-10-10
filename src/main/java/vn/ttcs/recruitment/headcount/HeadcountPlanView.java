package vn.ttcs.recruitment.headcount;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

// Task 273: a plan as HR sees it, with what the requisitions already use. A remaining value is negative when the
// department is over its plan (HR confirmed going over it, or lowered the plan afterwards).
// salaryBudget and salaryBudgetRemaining are null when the year has no budget limit.
public record HeadcountPlanView(UUID id, UUID departmentId, String departmentCode, String departmentName, int year,
                                int headcountLimit, long headcountUsed, long headcountRemaining,
                                Long salaryBudget, long salaryBudgetUsed, Long salaryBudgetRemaining,
                                Instant createdAt, Instant updatedAt, UUID updatedBy) {

    static HeadcountPlanView from(HeadcountPlanRepository.PlanWithDepartment row, HeadcountUsage usage) {
        HeadcountPlan plan = row.plan();
        return new HeadcountPlanView(plan.id(), plan.departmentId(), row.departmentCode(), row.departmentName(),
                plan.year(), plan.headcountLimit(), usage.headcount(), plan.headcountLimit() - usage.headcount(),
                plan.salaryBudget(), usage.salaryCost(),
                plan.salaryBudget() == null ? null : plan.salaryBudget() - usage.salaryCost(),
                plan.createdAt(), plan.updatedAt(), plan.updatedBy());
    }

    public record Page(List<HeadcountPlanView> items, int page, int size, long totalElements, long totalPages) { }
}
