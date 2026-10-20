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
                               List<Stage> stages, long totalDropped, LargestDrop largestDrop) {

    /**
     * reached: applications that got at least this far. advanced: those that got further (reached of the next stage).
     * conversionRate = advanced / reached. rejected (by the company), declined (the candidate declined the offer) and
     * withdrawn: applications whose furthest stage is this one and that ended there; inProgress: those still there.
     * dropped = rejected + declined + withdrawn; dropRate = dropped / reached.
     * Rates have 4 decimals and are null when reached is 0. The last stage has no next stage.
     */
    public record Stage(String stage, String label, String nextStage, long reached, long advanced,
                        BigDecimal conversionRate, long rejected, long declined, long withdrawn, long inProgress,
                        long dropped, BigDecimal dropRate) { }

    /**
     * Task 569: the stage that lost the most applications (rejected, declined or withdrawn there), with the numbers to
     * check it against the chart: the same reached/advanced/dropped as its stage row, its share of every drop of the
     * cohort and the most frequent reasons of its rejections and declines, each application counted once by its latest
     * REJECTED event. Null when nothing dropped. rule names the tie-break: most dropped, then the highest drop rate,
     * then the earliest stage.
     */
    public record LargestDrop(String stage, String label, String nextStage, long reached, long advanced,
                              BigDecimal conversionRate, long dropped, long rejected, long declined, long withdrawn,
                              BigDecimal dropRate, BigDecimal shareOfAllDrops, List<ReasonCount> topRejectionReasons,
                              String rule) { }

    /**
     * kind STAFF_REJECTION: the rejection reason recorded on the stage event (the catalog name at the time; "Không rõ lý
     * do" without one); kind CANDIDATE_DECLINE: the reason the candidate gave when declining the offer
     * (offers.decline_reason), "không nêu lý do" without one. count: applications whose latest REJECTED event says so.
     */
    public record ReasonCount(String kind, String reason, long count) { }
}
