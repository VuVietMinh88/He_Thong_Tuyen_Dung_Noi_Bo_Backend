package vn.ttcs.recruitment.position;

import java.util.Objects;
import java.util.UUID;

// Jira 206: the standard salary band of an active position, in whole VND, that offer approval and requisition
// checks compare a proposed salary against. It carries salary amounts, so a caller may put salaryMin/salaryMax
// in a response only for users with SALARY_RANGES_READ_ALL, the same rule PositionView follows.
public record SalaryBand(UUID positionId, long salaryMin, long salaryMax) {

    // V7 guarantees 0 <= salary_min <= salary_max for stored rows; a band built in code keeps the same rule.
    public SalaryBand {
        Objects.requireNonNull(positionId, "positionId");
        if (salaryMin < 0 || salaryMin > salaryMax) {
            throw new IllegalArgumentException("A salary band needs 0 <= salaryMin <= salaryMax");
        }
    }

    // Both ends are inside the band, so a fixed band (salaryMin == salaryMax) accepts exactly one amount.
    // A negative amount means the caller skipped its own request validation; failing here is safer than
    // quietly reporting it as BELOW.
    public SalaryBandComparison compare(long proposedSalary) {
        if (proposedSalary < 0) {
            throw new IllegalArgumentException("A proposed salary cannot be negative");
        }
        if (proposedSalary < salaryMin) {
            return SalaryBandComparison.BELOW;
        }
        return proposedSalary > salaryMax ? SalaryBandComparison.ABOVE : SalaryBandComparison.WITHIN;
    }
}
