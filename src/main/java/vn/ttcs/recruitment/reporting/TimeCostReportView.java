package vn.ttcs.recruitment.reporting;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Task 574 (story S8-08): time to hire and cost per hire of a period, overall and per requisition.
 * Hires are counted by the business date they entered HIRED; costs by the date they were incurred.
 */
public record TimeCostReportView(Instant generatedAt, LocalDate from, LocalDate to, String scope, Metrics overall,
                                 List<RequisitionRow> requisitions) {

    /**
     * hires: applications that entered HIRED in the period; of them, hiresWithAcceptedOffer have an accepted offer and
     * hiresOnboarded completed onboarding. costsRecorded: at least one cost entry (not voided) in the period.
     * costPerHireVnd = costs.totalVnd / hires (whole VND), null without a hire and null when no cost was recorded.
     */
    public record Metrics(long hires, long hiresWithAcceptedOffer, long hiresOnboarded, TimeToHire timeToHire,
                          Costs costs, boolean costsRecorded, Long costPerHireVnd) { }

    /**
     * Days from the requisition's final approval to the hire, in business dates, over sampleSize hires (averageDays
     * with 1 decimal, weighted by hires; null without a sample). missingApproval: hires whose requisition has no final
     * approval event; invalid: hires dated before the approval. Neither enters the average.
     */
    public record TimeToHire(long sampleSize, BigDecimal averageDays, Long minDays, Long maxDays, long missingApproval,
                             long invalid) { }

    /** Costs not voided, incurred in the period: advertising, referral bonuses, their total and number of entries. */
    public record Costs(long advertisingVnd, long referralBonusVnd, long totalVnd, long entries) { }

    /** approvedOn: the business date of the requisition's final approval, null when it has none. */
    public record RequisitionRow(UUID requisitionId, UUID departmentId, String departmentName, UUID positionId,
                                 String positionName, String status, LocalDate approvedOn, Metrics metrics) { }
}
