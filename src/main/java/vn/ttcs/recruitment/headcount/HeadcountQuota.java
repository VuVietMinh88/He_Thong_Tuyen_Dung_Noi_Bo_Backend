package vn.ttcs.recruitment.headcount;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import vn.ttcs.recruitment.common.ApiException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Task 274: checks a requisition save against the headcount plan of its department and plan year (story S3-04:
 * "Vượt chỉ tiêu là cảnh báo chặn"). Called by RequisitionService inside its write transaction, after its own checks.
 */
@Component
public class HeadcountQuota {
    private final HeadcountPlanRepository plans;
    private final HeadcountOverrideRepository overrides;

    public HeadcountQuota(HeadcountPlanRepository plans, HeadcountOverrideRepository overrides) {
        this.plans = plans;
        this.overrides = overrides;
    }

    /**
     * Locks the plan of demand's department and year, then compares the demand with what the other requisitions use.
     * Empty when there is no plan (no limit) or the save fits.
     * <p>
     * Concurrency: the plan row stays locked FOR UPDATE until the caller commits. Two saves for the same department
     * and year therefore run one after the other, and the second one (a new statement under READ COMMITTED) counts
     * the requisition the first one committed. Lock order of a requisition save: account, session, requisition,
     * position and department FOR SHARE, then this plan; HeadcountPlanService.update never locks a department, so the
     * two cannot wait for each other.
     * <p>
     * previous is the same requisition as saved before (update), or null (create). A save is only blocked when it asks
     * more of this plan than before: a new requisition, one moved here from another department or year, more people
     * (for the headcount limit) or a higher salary cost (for the budget). So a draft that is already over the plan,
     * because HR confirmed it or lowered the plan later, can still be edited without asking for more.
     *
     * @param requisitionId the requisition being saved again, left out of the usage; null on create
     */
    public Optional<HeadcountExcess> check(HeadcountDemand demand, HeadcountDemand previous, UUID requisitionId) {
        Optional<HeadcountPlan> found = plans.findByDepartmentAndYearForUpdate(demand.departmentId(), demand.year());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        HeadcountPlan plan = found.get();
        HeadcountUsage others = plans.usage(demand.departmentId(), demand.year(), requisitionId);
        boolean samePlan = demand.samePlanAs(previous);
        boolean morePeople = !samePlan || demand.headcount() > previous.headcount();
        boolean moreCost = !samePlan || demand.salaryCost() > previous.salaryCost();
        boolean headcount = morePeople && others.headcount() + demand.headcount() > plan.headcountLimit();
        boolean salaryBudget = moreCost && plan.salaryBudget() != null
                && saturatedAdd(others.salaryCost(), demand.salaryCost()) > plan.salaryBudget();
        if (!headcount && !salaryBudget) {
            return Optional.empty();
        }
        return Optional.of(new HeadcountExcess(plan, others, demand, headcount, salaryBudget));
    }

    /**
     * The 409 for a save over the plan. The headcount message names the people left, which every requisition writer
     * of the department may know; the budget message never names an amount, because only HR may see the budget.
     */
    public static ApiException exceeded(HeadcountExcess excess) {
        String confirm = " Cần Trưởng phòng Nhân sự xác nhận vượt định biên kèm lý do.";
        int year = excess.plan().year();
        if (excess.headcount()) {
            String message = "Phòng ban chỉ còn " + excess.headcountLeft() + " chỉ tiêu headcount năm " + year
                    + " nhưng yêu cầu cần " + excess.demand().headcount() + " người.";
            if (excess.salaryBudget()) {
                message += " Yêu cầu cũng vượt ngân sách lương năm " + year + ".";
            }
            return new ApiException(HttpStatus.CONFLICT, "HEADCOUNT_LIMIT_EXCEEDED", message + confirm);
        }
        return new ApiException(HttpStatus.CONFLICT, "SALARY_BUDGET_EXCEEDED",
                "Yêu cầu vượt ngân sách lương năm " + year + " của phòng ban." + confirm);
    }

    /**
     * Task 275: records that an HR manager confirmed this save over the plan. The caller has already checked the
     * permission and the reason, and saved the requisition (the row references it). Runs in the same transaction,
     * while the plan row is still locked, so the numbers stored are exactly those the save was checked against.
     */
    public void recordOverride(HeadcountExcess excess, UUID requisitionId, String reason, UUID overriddenBy,
                               Instant overriddenAt) {
        overrides.insert(HeadcountOverride.of(excess, requisitionId, reason, overriddenBy, overriddenAt));
    }

    // Task 275: the HR exceptions of one requisition, newest first.
    public List<HeadcountOverride> overridesOf(UUID requisitionId) {
        return overrides.findByRequisition(requisitionId);
    }

    // Both amounts are never negative; the sum stops at Long.MAX_VALUE like the usage itself.
    private static long saturatedAdd(long left, long right) {
        long sum = left + right;
        return sum < 0 ? Long.MAX_VALUE : sum;
    }
}
