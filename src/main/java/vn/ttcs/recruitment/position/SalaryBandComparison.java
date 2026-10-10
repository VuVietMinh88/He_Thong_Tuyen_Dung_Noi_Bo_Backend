package vn.ttcs.recruitment.position;

/** Where a proposed salary falls in a position's standard salary band. Both ends of the band count as inside. */
public enum SalaryBandComparison {
    /** Lower than salaryMin. */
    BELOW,
    /** From salaryMin to salaryMax, both included. */
    WITHIN,
    /** Higher than salaryMax, so the company-approved limit is exceeded. */
    ABOVE
}
