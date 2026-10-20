package vn.ttcs.recruitment.reporting;

import org.junit.jupiter.api.Test;
import vn.ttcs.recruitment.reporting.FunnelCalculator.Group;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Task 567: funnel counting rules, conversion and zero denominators. */
class FunnelCalculatorTest {

    @Test
    void reachedCountsEveryApplicationThatGotAtLeastThatFarAndNeverIncreases() {
        var result = FunnelCalculator.compute(List.of(
                new Group(1, "NEW", false, 4),            // still new
                new Group(1, "REJECTED", false, 3),       // rejected before screening
                new Group(2, "WITHDRAWN", false, 1),      // withdrew during screening
                new Group(2, "SCREENING", false, 2),
                new Group(3, "REJECTED", false, 2),       // rejected after the interview
                new Group(4, "OFFER", true, 1),           // HR skipped the interview
                new Group(5, "HIRED", false, 1),
                new Group(6, "ONBOARDED", false, 1)));
        assertThat(result.cohortSize()).isEqualTo(15);
        assertThat(result.interviewSkipped()).isEqualTo(1);
        assertThat(result.hired()).isEqualTo(2);
        assertThat(result.stages()).extracting(FunnelCalculator.StageNumbers::reached)
                .containsExactly(15L, 8L, 5L, 3L, 2L, 1L);
        assertThat(result.stages()).extracting(FunnelCalculator.StageNumbers::advanced)
                .containsExactly(8L, 5L, 3L, 2L, 1L, 0L);
        var created = result.stages().get(0);
        assertThat(created.rejected()).isEqualTo(3);
        assertThat(created.inProgress()).isEqualTo(4);
        assertThat(created.dropped()).isEqualTo(3);
        var screening = result.stages().get(1);
        assertThat(screening.withdrawn()).isEqualTo(1);
        assertThat(screening.inProgress()).isEqualTo(2);
        assertThat(result.stages().get(2).rejected()).isEqualTo(2);
        assertThat(result.stages().get(3).inProgress()).isEqualTo(1);
        assertThat(result.stages().get(4).inProgress()).isEqualTo(1);     // hired, onboarding not completed yet
        for (var stage : result.stages()) {
            assertThat(stage.reached()).isEqualTo(stage.advanced() + stage.dropped() + stage.inProgress());
        }
    }

    @Test
    void anEmptyCohortHasZeroCountsAndNoRates() {
        var result = FunnelCalculator.compute(List.of());
        assertThat(result.cohortSize()).isZero();
        assertThat(result.stages()).hasSize(6).allSatisfy(stage -> assertThat(stage.reached()).isZero());
        assertThat(ReportRates.rate(0, 0)).isNull();
        assertThat(ReportRates.rate(1, 3)).isEqualByComparingTo(new BigDecimal("0.3333"));
        assertThat(ReportRates.rate(2, 3)).isEqualByComparingTo(new BigDecimal("0.6667"));
        assertThat(ReportRates.average(7, 2)).isEqualByComparingTo(new BigDecimal("3.5"));
        assertThat(ReportRates.average(1, 0)).isNull();
        assertThat(ReportRates.perUnit(10, 3)).isEqualTo(3L);
        assertThat(ReportRates.perUnit(11, 2)).isEqualTo(6L);
        assertThat(ReportRates.perUnit(5, 0)).isNull();
    }

    @Test
    void theLargestDropIsTheMostDroppedThenTheHighestRateThenTheEarliestStage() {
        // NEW drops 2 of 10 (20 %), SCREENING drops 2 of 5 (40 %): the same count, the higher rate wins.
        var tie = FunnelCalculator.compute(List.of(new Group(1, "REJECTED", false, 2), new Group(1, "NEW", false, 3),
                new Group(2, "REJECTED", false, 1), new Group(2, "WITHDRAWN", false, 1), new Group(3, "INTERVIEW", false, 3)));
        assertThat(FunnelCalculator.largestDrop(tie)).hasValueSatisfying(stage -> {
            assertThat(stage.stage().name()).isEqualTo("SCREENING");
            assertThat(stage.dropped()).isEqualTo(2);
        });
        // A larger count wins over a higher rate.
        var count = FunnelCalculator.compute(List.of(new Group(1, "REJECTED", false, 3), new Group(1, "NEW", false, 7),
                new Group(2, "REJECTED", false, 2), new Group(3, "OFFER", false, 0)));
        assertThat(FunnelCalculator.largestDrop(count)).hasValueSatisfying(stage ->
                assertThat(stage.stage().name()).isEqualTo("NEW"));
        // Two stages with the same drop count always differ in reached (an earlier stage that drops has more), so
        // the rate decides; the earliest-stage rule only keeps the choice deterministic.
        assertThat(FunnelCalculator.largestDrop(FunnelCalculator.compute(List.of(new Group(2, "SCREENING", false, 4)))))
                .isEmpty();
    }

    @Test
    void aCandidateWhoDeclinedTheOfferIsADropOfItsOwnKind() {
        var result = FunnelCalculator.compute(List.of(new Group(4, "REJECTED", false, true, 2),
                new Group(4, "REJECTED", false, false, 1), new Group(4, "OFFER", false, 1), new Group(5, "HIRED", false, 1)));
        var offer = result.stages().get(3);
        assertThat(offer.reached()).isEqualTo(5);
        assertThat(offer.declined()).isEqualTo(2);
        assertThat(offer.rejected()).isEqualTo(1);
        assertThat(offer.dropped()).isEqualTo(3);
        assertThat(offer.inProgress()).isEqualTo(1);
        assertThat(offer.reached()).isEqualTo(offer.advanced() + offer.dropped() + offer.inProgress());
        assertThat(FunnelCalculator.largestDrop(result).orElseThrow().stage().name()).isEqualTo("OFFER");
    }

    @Test
    void anUnknownRankIsAProgrammingError() {
        assertThatThrownBy(() -> FunnelCalculator.compute(List.of(new Group(0, "NEW", false, 1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FunnelCalculator.compute(List.of(new Group(7, "NEW", false, 1))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
