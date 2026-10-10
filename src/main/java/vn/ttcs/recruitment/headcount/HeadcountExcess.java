package vn.ttcs.recruitment.headcount;

// Task 274: a requisition save that would go over its plan. others is what the other requisitions already use;
// headcount and salaryBudget say which limit is exceeded (at least one of them).
public record HeadcountExcess(HeadcountPlan plan, HeadcountUsage others, HeadcountDemand demand,
                              boolean headcount, boolean salaryBudget) {

    // People still free in the plan before this save, never below 0.
    public long headcountLeft() {
        return Math.max(0, plan.headcountLimit() - others.headcount());
    }
}
