package vn.ttcs.recruitment.headcount;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

// Task 275: one HR exception as GET /requisitions/{id}/headcount-overrides returns it. The three salary amounts are
// only for callers with HEADCOUNT_PLANS_READ_ALL; for everyone else they are null and NON_NULL leaves the keys out,
// like the salary budget of the remaining lookup. For HR, salaryBudget is also left out when the plan had no budget.
public record HeadcountOverrideView(UUID id, UUID departmentId, int year, String reason, UUID overriddenBy,
                                    Instant overriddenAt, boolean headcountExceeded, boolean salaryBudgetExceeded,
                                    int headcountLimit, long headcountUsed, int requestedHeadcount,
                                    @JsonInclude(JsonInclude.Include.NON_NULL) Long salaryBudget,
                                    @JsonInclude(JsonInclude.Include.NON_NULL) Long salaryCostUsed,
                                    @JsonInclude(JsonInclude.Include.NON_NULL) Long requestedSalaryCost) {

    public static HeadcountOverrideView from(HeadcountOverride override, boolean showSalary) {
        return new HeadcountOverrideView(override.id(), override.departmentId(), override.year(), override.reason(),
                override.overriddenBy(), override.overriddenAt(), override.headcountExceeded(),
                override.salaryBudgetExceeded(), override.headcountLimit(), override.headcountUsed(),
                override.requestedHeadcount(), showSalary ? override.salaryBudget() : null,
                showSalary ? override.salaryCostUsed() : null, showSalary ? override.requestedSalaryCost() : null);
    }
}
