package vn.ttcs.recruitment.reporting;

import org.junit.jupiter.api.Test;
import vn.ttcs.recruitment.reporting.TimeCostCalculator.Accumulator;
import vn.ttcs.recruitment.reporting.TimeCostCalculator.Cost;
import vn.ttcs.recruitment.reporting.TimeCostCalculator.Hire;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Task 574: the time-to-hire and cost-per-hire rules. */
class TimeCostCalculatorTest {
    private static final UUID REQUISITION = UUID.randomUUID();
    private static final LocalDate APPROVED = LocalDate.of(2026, 9, 1);

    @Test
    void daysAreBusinessDatesFromTheApprovalAndTheAverageIsWeightedByHires() {
        var metrics = new Accumulator()
                .add(new Hire(REQUISITION, LocalDate.of(2026, 9, 1), APPROVED, true, false))      // same day: 0
                .add(new Hire(REQUISITION, LocalDate.of(2026, 9, 11), APPROVED, false, true))
                .add(new Hire(REQUISITION, LocalDate.of(2026, 9, 22), APPROVED, true, true))
                .add(new Hire(REQUISITION, LocalDate.of(2026, 9, 5), null, false, false))          // no approval
                .add(new Hire(REQUISITION, LocalDate.of(2026, 8, 30), APPROVED, false, false))     // before it
                .metrics();
        assertThat(metrics.hires()).isEqualTo(5);
        assertThat(metrics.hiresWithAcceptedOffer()).isEqualTo(2);
        assertThat(metrics.hiresOnboarded()).isEqualTo(2);
        assertThat(metrics.timeToHire().sampleSize()).isEqualTo(3);
        assertThat(metrics.timeToHire().averageDays()).isEqualByComparingTo("10.3");          // (0 + 10 + 21) / 3
        assertThat(metrics.timeToHire().minDays()).isZero();
        assertThat(metrics.timeToHire().maxDays()).isEqualTo(21);
        assertThat(metrics.timeToHire().missingApproval()).isEqualTo(1);
        assertThat(metrics.timeToHire().invalid()).isEqualTo(1);
        assertThat(metrics.costs().totalVnd()).isZero();
        // No cost recorded: the cost per hire is unknown, not zero.
        assertThat(metrics.costsRecorded()).isFalse();
        assertThat(metrics.costPerHireVnd()).isNull();
    }

    @Test
    void costPerHireDividesTheCostsByTheHiresAndIsNullWithoutAHire() {
        var withHires = new Accumulator()
                .add(new Cost(REQUISITION, "ADVERTISING", 3_000_000, 2))
                .add(new Cost(REQUISITION, "REFERRAL_BONUS", 1_000_001, 1))
                .add(new Hire(REQUISITION, LocalDate.of(2026, 9, 3), APPROVED, true, false))
                .add(new Hire(REQUISITION, LocalDate.of(2026, 9, 4), APPROVED, true, false))
                .metrics();
        assertThat(withHires.costs().advertisingVnd()).isEqualTo(3_000_000);
        assertThat(withHires.costs().referralBonusVnd()).isEqualTo(1_000_001);
        assertThat(withHires.costs().totalVnd()).isEqualTo(4_000_001);
        assertThat(withHires.costs().entries()).isEqualTo(3);
        assertThat(withHires.costsRecorded()).isTrue();
        assertThat(withHires.costPerHireVnd()).isEqualTo(2_000_001L);              // 2,000,000.5 rounded half up

        var noHire = new Accumulator().add(new Cost(REQUISITION, "ADVERTISING", 5_000_000, 1)).metrics();
        assertThat(noHire.hires()).isZero();
        assertThat(noHire.costsRecorded()).isTrue();
        assertThat(noHire.costPerHireVnd()).isNull();
        assertThat(noHire.timeToHire().averageDays()).isNull();
        assertThat(noHire.timeToHire().minDays()).isNull();

        var empty = new Accumulator().metrics();
        assertThat(empty.costs().totalVnd()).isZero();
        assertThat(empty.costsRecorded()).isFalse();
        assertThat(empty.costPerHireVnd()).isNull();
        assertThatThrownBy(() -> new Accumulator().add(new Cost(REQUISITION, "SALARY", 1, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
