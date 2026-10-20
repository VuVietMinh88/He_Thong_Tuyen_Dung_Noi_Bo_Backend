package vn.ttcs.recruitment.reporting;

import vn.ttcs.recruitment.application.ApplicationStage;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Tasks 567 and 569 (story S8-07): the funnel numbers of a cohort, from one row per (furthest stage reached, current stage,
 * interview skipped) with its number of applications (ApplicationReach). Pure, so every rule is unit-tested.
 * <ul>
 * <li>reached(k): applications whose furthest stage is k or later. Monotone: a later stage never has more.</li>
 * <li>advanced(k) = reached(k + 1); conversionRate(k) = advanced / reached, null when reached is 0.</li>
 * <li>Of the applications whose furthest stage is k: rejected (by the company), declined (the candidate declined the
 * offer: the latest REJECTED event is the candidate's answer) and withdrawn are the drops; the others are still there
 * (inProgress), not lost yet. dropRate = dropped / reached.</li>
 * </ul>
 */
final class FunnelCalculator {
    static final List<ApplicationStage> PIPELINE = List.of(ApplicationStage.NEW, ApplicationStage.SCREENING,
            ApplicationStage.INTERVIEW, ApplicationStage.OFFER, ApplicationStage.HIRED, ApplicationStage.ONBOARDED);

    static final String LARGEST_DROP_RULE = "MOST_DROPPED_THEN_HIGHEST_DROP_RATE_THEN_EARLIEST_STAGE";

    private FunnelCalculator() {
    }

    /**
     * Task 569: the stage with the most drops (rejected + declined + withdrawn there); a tie goes to the higher drop rate
     * (compared exactly, without rounding), then to the earlier stage. Empty when no application dropped.
     */
    static Optional<StageNumbers> largestDrop(Result result) {
        StageNumbers largest = null;
        for (StageNumbers stage : result.stages()) {
            if (stage.dropped() == 0) {
                continue;
            }
            if (largest == null || stage.dropped() > largest.dropped()
                    || (stage.dropped() == largest.dropped()
                    && Math.multiplyExact(stage.dropped(), largest.reached())
                    > Math.multiplyExact(largest.dropped(), stage.reached()))) {
                largest = stage;
            }
        }
        return Optional.ofNullable(largest);
    }

    /**
     * One group of applications: furthest rank 1..6 (ApplicationReach), current stage, skipped flag, whether a REJECTED
     * application ended by the candidate declining the offer, count.
     */
    record Group(int maxRank, String currentStage, boolean skipped, boolean declined, long count) {
        Group(int maxRank, String currentStage, boolean skipped, long count) {
            this(maxRank, currentStage, skipped, false, count);
        }
    }

    record StageNumbers(ApplicationStage stage, long reached, long advanced, long rejected, long declined,
                        long withdrawn, long inProgress) {
        long dropped() {
            return rejected + declined + withdrawn;
        }
    }

    record Result(long cohortSize, long interviewSkipped, List<StageNumbers> stages) {
        long hired() {
            return stages.get(PIPELINE.indexOf(ApplicationStage.HIRED)).reached();
        }
    }

    static Result compute(List<Group> groups) {
        int size = PIPELINE.size();
        long[] furthest = new long[size + 1];
        long[] rejected = new long[size + 1];
        long[] declined = new long[size + 1];
        long[] withdrawn = new long[size + 1];
        long cohort = 0;
        long skipped = 0;
        for (Group group : groups) {
            if (group.maxRank() < 1 || group.maxRank() > size) {
                throw new IllegalArgumentException("Unknown pipeline rank " + group.maxRank());
            }
            cohort += group.count();
            skipped += group.skipped() ? group.count() : 0;
            furthest[group.maxRank()] += group.count();
            if ("REJECTED".equals(group.currentStage()) && group.declined()) {
                declined[group.maxRank()] += group.count();
            } else if ("REJECTED".equals(group.currentStage())) {
                rejected[group.maxRank()] += group.count();
            } else if ("WITHDRAWN".equals(group.currentStage())) {
                withdrawn[group.maxRank()] += group.count();
            }
        }
        long[] reached = new long[size + 2];
        for (int rank = size; rank >= 1; rank--) {
            reached[rank] = reached[rank + 1] + furthest[rank];
        }
        List<StageNumbers> stages = new ArrayList<>();
        for (int rank = 1; rank <= size; rank++) {
            long dropped = rejected[rank] + declined[rank] + withdrawn[rank];
            stages.add(new StageNumbers(PIPELINE.get(rank - 1), reached[rank], reached[rank + 1], rejected[rank],
                    declined[rank], withdrawn[rank], furthest[rank] - dropped));
        }
        return new Result(cohort, skipped, List.copyOf(stages));
    }
}
