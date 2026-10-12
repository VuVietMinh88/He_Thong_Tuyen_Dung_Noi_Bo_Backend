package vn.ttcs.recruitment.position;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static vn.ttcs.recruitment.position.SalaryBandComparison.ABOVE;
import static vn.ttcs.recruitment.position.SalaryBandComparison.BELOW;
import static vn.ttcs.recruitment.position.SalaryBandComparison.WITHIN;

class SalaryBandTest {
    private static final UUID POSITION = UUID.fromString("00000000-0000-0000-0000-000000000206");

    @Test
    void bothEndsOfTheBandAreInsideAndOneDongOutsideIsBelowOrAbove() {
        var band = new SalaryBand(POSITION, 15_000_000L, 25_000_000L);

        assertThat(band.compare(0L)).isEqualTo(BELOW);
        assertThat(band.compare(14_999_999L)).isEqualTo(BELOW);
        assertThat(band.compare(15_000_000L)).isEqualTo(WITHIN);
        assertThat(band.compare(20_000_000L)).isEqualTo(WITHIN);
        assertThat(band.compare(25_000_000L)).isEqualTo(WITHIN);
        assertThat(band.compare(25_000_001L)).isEqualTo(ABOVE);
        assertThat(band.compare(Long.MAX_VALUE)).isEqualTo(ABOVE);
    }

    @Test
    void fixedBandAcceptsExactlyOneAmount() {
        var band = new SalaryBand(POSITION, 20_000_000L, 20_000_000L);

        assertThat(band.compare(19_999_999L)).isEqualTo(BELOW);
        assertThat(band.compare(20_000_000L)).isEqualTo(WITHIN);
        assertThat(band.compare(20_000_001L)).isEqualTo(ABOVE);
    }

    @Test
    void bandStartingAtZeroHasNothingBelowIt() {
        var band = new SalaryBand(POSITION, 0L, 5_000_000L);

        assertThat(band.compare(0L)).isEqualTo(WITHIN);
        assertThat(band.compare(5_000_001L)).isEqualTo(ABOVE);
    }

    @Test
    void comparesAmountsLargerThanAnIntWithoutOverflow() {
        // Both ends are above Integer.MAX_VALUE (2.147.483.647), so the band must stay long end to end.
        var band = new SalaryBand(POSITION, 1_500_000_000L, 3_000_000_000L);

        assertThat(band.compare(2_147_483_648L)).isEqualTo(WITHIN);
        assertThat(band.compare(3_000_000_000L)).isEqualTo(WITHIN);
        assertThat(band.compare(3_000_000_001L)).isEqualTo(ABOVE);
        assertThat(band.compare(1_499_999_999L)).isEqualTo(BELOW);
    }

    @Test
    void negativeProposedSalaryIsAProgrammingErrorNotBelowTheBand() {
        var band = new SalaryBand(POSITION, 0L, 5_000_000L);

        assertThatThrownBy(() -> band.compare(-1L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("negative");
    }

    @Test
    void bandKeepsTheSameRulesAsTheDatabaseCheck() {
        assertThatThrownBy(() -> new SalaryBand(null, 1L, 2L)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SalaryBand(POSITION, -1L, 2L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SalaryBand(POSITION, 3L, 2L)).isInstanceOf(IllegalArgumentException.class);

        var fixed = new SalaryBand(POSITION, 2L, 2L);
        assertThat(fixed.salaryMin()).isEqualTo(2L);
        assertThat(fixed.salaryMax()).isEqualTo(2L);
    }
}
