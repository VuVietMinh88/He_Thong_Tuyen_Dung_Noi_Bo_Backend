package vn.ttcs.recruitment.headcount;

import java.time.Instant;
import java.util.UUID;

// Task 275: one row of requisition_headcount_overrides (V15): an HR manager confirmed a requisition save over the
// plan of departmentId and year. The numbers are those of that moment.
public record HeadcountOverride(UUID id, UUID requisitionId, UUID departmentId, int year, int headcountLimit,
                                Long salaryBudget, long headcountUsed, long salaryCostUsed, int requestedHeadcount,
                                long requestedSalaryCost, boolean headcountExceeded, boolean salaryBudgetExceeded,
                                String reason, UUID overriddenBy, Instant overriddenAt) {

    static HeadcountOverride of(HeadcountExcess excess, UUID requisitionId, String reason, UUID overriddenBy,
                                Instant overriddenAt) {
        HeadcountPlan plan = excess.plan();
        return new HeadcountOverride(UUID.randomUUID(), requisitionId, plan.departmentId(), plan.year(),
                plan.headcountLimit(), plan.salaryBudget(), excess.others().headcount(),
                excess.others().salaryCost(), Math.toIntExact(excess.demand().headcount()),
                excess.demand().salaryCost(), excess.headcount(), excess.salaryBudget(), reason, overriddenBy,
                overriddenAt);
    }
}
