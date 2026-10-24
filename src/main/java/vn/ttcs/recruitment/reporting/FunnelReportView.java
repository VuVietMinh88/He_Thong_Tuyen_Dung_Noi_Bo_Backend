package vn.ttcs.recruitment.reporting;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Task 567 (story S8-07): the recruitment funnel of a cohort, the applications received between from and to (business
 * dates, inclusive), each followed through its stage history up to generatedAt.
 */
public record FunnelReportView(Instant generatedAt, LocalDate from, LocalDate to, String scope, ReportFilters filters,
                               long cohortSize,
                               long hired, BigDecimal overallConversionRate, long interviewSkipped,
                               List<Stage> stages) {

    /**
     * reached: applications that got at least this far. advanced: those that got further (reached of the next stage).
     * conversionRate = advanced / reached. rejected and withdrawn: applications whose furthest stage is this one and
     * that ended there; inProgress: those still there. dropped = rejected + withdrawn; dropRate = dropped / reached.
     * Rates have 4 decimals and are null when reached is 0. The last stage has no next stage.
     */
    public record Stage(String stage, String label, String nextStage, long reached, long advanced,
                        BigDecimal conversionRate, long rejected, long withdrawn, long inProgress, long dropped,
                        BigDecimal dropRate) { }
}
