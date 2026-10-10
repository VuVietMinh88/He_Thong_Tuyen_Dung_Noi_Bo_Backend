package vn.ttcs.recruitment.headcount;

import java.time.LocalDate;
import java.util.UUID;

// Task 274: what one requisition asks of its department's plan: the plan year, the people and the yearly salary cost,
// with the rules of HeadcountUsage.
public record HeadcountDemand(UUID departmentId, int year, long headcount, long salaryCost) {

    // createdOn: the business date the requisition was (or is being) created, used when neededBy is empty.
    public static HeadcountDemand of(UUID departmentId, LocalDate neededBy, LocalDate createdOn, int headcount,
                                     Long proposedSalaryMin, Long proposedSalaryMax) {
        return new HeadcountDemand(departmentId, HeadcountUsage.planYear(neededBy, createdOn), headcount,
                HeadcountUsage.salaryCost(headcount, proposedSalaryMin, proposedSalaryMax));
    }

    boolean samePlanAs(HeadcountDemand other) {
        return other != null && departmentId.equals(other.departmentId) && year == other.year;
    }
}
