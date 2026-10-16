package vn.ttcs.recruitment.headcount;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * Task 273: what the requisition form shows for one department and year (story S3-04: "Yêu cầu tuyển dụng mới hiển
 * thị số headcount còn lại của phòng ban"). planned=false means HR has declared no plan for that year: there is no
 * limit, so headcountLimit and headcountRemaining are null. headcountRemaining is negative when the department is
 * over its plan.
 * The four salary fields are for callers with HEADCOUNT_PLANS_READ_ALL only. For everyone else they are null and
 * NON_NULL leaves the keys out of the JSON, like the salary band of a position. For HR, salaryBudgetLimited=false
 * means the year has no budget limit (no plan, or a plan without budget): salaryBudget and salaryBudgetRemaining are
 * then left out, and salaryBudgetUsed is still shown.
 */
public record HeadcountRemainingView(UUID departmentId, int year, boolean planned, Integer headcountLimit,
                                     long headcountUsed, Long headcountRemaining,
                                     @JsonInclude(JsonInclude.Include.NON_NULL) Boolean salaryBudgetLimited,
                                     @JsonInclude(JsonInclude.Include.NON_NULL) Long salaryBudget,
                                     @JsonInclude(JsonInclude.Include.NON_NULL) Long salaryBudgetUsed,
                                     @JsonInclude(JsonInclude.Include.NON_NULL) Long salaryBudgetRemaining) {

    static HeadcountRemainingView of(UUID departmentId, int year, HeadcountPlan plan, HeadcountUsage usage,
                                     boolean showSalaryBudget) {
        Integer limit = plan == null ? null : plan.headcountLimit();
        Long remaining = limit == null ? null : limit - usage.headcount();
        if (!showSalaryBudget) {
            return new HeadcountRemainingView(departmentId, year, plan != null, limit, usage.headcount(), remaining,
                    null, null, null, null);
        }
        Long budget = plan == null ? null : plan.salaryBudget();
        return new HeadcountRemainingView(departmentId, year, plan != null, limit, usage.headcount(), remaining,
                budget != null, budget, usage.salaryCost(), budget == null ? null : budget - usage.salaryCost());
    }
}
