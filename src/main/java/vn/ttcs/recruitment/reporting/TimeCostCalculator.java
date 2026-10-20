package vn.ttcs.recruitment.reporting;

import vn.ttcs.recruitment.reporting.TimeCostReportView.Costs;
import vn.ttcs.recruitment.reporting.TimeCostReportView.Metrics;
import vn.ttcs.recruitment.reporting.TimeCostReportView.TimeToHire;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Task 574 (story S8-08): time to hire and cost per hire of a group of hires and costs (a requisition, a department, a
 * position or everything), pure so every rule is unit-tested.
 * <ul>
 * <li>A hire is the first time an application entered HIRED ("Nhận việc": the candidate accepted the job, usually by
 * accepting the offer), inside the period.</li>
 * <li>Time to hire of one hire = its business date minus the business date of the requisition's final approval (the
 * first APPROVED event that made the requisition APPROVED). A hire whose requisition has no such event is counted in
 * missingApproval; one dated before the approval (inconsistent data) in invalid; neither enters the average.</li>
 * <li>The average is weighted by hires (the mean of every hire's days, never a mean of requisition means).</li>
 * <li>Cost per hire = the costs (not voided) incurred in the period / the hires of the period, rounded to whole VND;
 * null when there is no hire (a requisition with costs and no hire shows its costs and no cost per hire) and null when
 * no cost was recorded in the period (costsRecorded false): an unrecorded cost is unknown, not zero. Every entry is at
 * least 1 VND, so recorded costs never add up to 0.</li>
 * </ul>
 */
final class TimeCostCalculator {
    private TimeCostCalculator() {
    }

    record Hire(UUID requisitionId, LocalDate hiredOn, LocalDate approvedOn, boolean acceptedOffer, boolean onboarded) { }

    record Cost(UUID requisitionId, String costType, long totalVnd, long entries) { }

    /** Collects hires and costs of one group. Not thread-safe; one per group and report. */
    static final class Accumulator {
        private long hires;
        private long acceptedOffers;
        private long onboarded;
        private long samples;
        private long totalDays;
        private Long minDays;
        private Long maxDays;
        private long missingApproval;
        private long invalid;
        private long advertising;
        private long referral;
        private long entries;

        Accumulator add(Hire hire) {
            hires++;
            acceptedOffers += hire.acceptedOffer() ? 1 : 0;
            onboarded += hire.onboarded() ? 1 : 0;
            if (hire.approvedOn() == null) {
                missingApproval++;
                return this;
            }
            long days = ChronoUnit.DAYS.between(hire.approvedOn(), hire.hiredOn());
            if (days < 0) {
                invalid++;
                return this;
            }
            samples++;
            totalDays += days;
            minDays = minDays == null ? days : Math.min(minDays, days);
            maxDays = maxDays == null ? days : Math.max(maxDays, days);
            return this;
        }

        Accumulator add(Cost cost) {
            switch (cost.costType()) {
                case "ADVERTISING" -> advertising = Math.addExact(advertising, cost.totalVnd());
                case "REFERRAL_BONUS" -> referral = Math.addExact(referral, cost.totalVnd());
                default -> throw new IllegalArgumentException("Unknown cost type " + cost.costType());
            }
            entries += cost.entries();
            return this;
        }

        Metrics metrics() {
            long total = Math.addExact(advertising, referral);
            boolean costsRecorded = entries > 0;
            return new Metrics(hires, acceptedOffers, onboarded,
                    new TimeToHire(samples, ReportRates.average(totalDays, samples), minDays, maxDays, missingApproval,
                            invalid),
                    new Costs(advertising, referral, total, entries), costsRecorded,
                    costsRecorded ? ReportRates.perUnit(total, hires) : null);
        }
    }
}
