package vn.ttcs.recruitment.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OnboardingProgressTest {

    @ParameterizedTest(name = "{0}/{1} -> {2}% canComplete={3}")
    @CsvSource({
            // completed, total, percent, canComplete
            "0, 0, 0, false",
            "0, 5, 0, false",
            "1, 3, 33, false",
            "2, 3, 66, false",
            "99, 100, 99, false",
            "5, 5, 100, true",
            "1, 1, 100, true",
            "1, 2147483647, 0, false",
            "2147483646, 2147483647, 99, false",
            "2147483647, 2147483647, 100, true"
    })
    void calculatesProgressAndCompletion(int completed, int total, int expectedPercent, boolean expectedCanComplete) {
        assertThat(OnboardingProgress.percent(completed, total)).isEqualTo(expectedPercent);
        assertThat(OnboardingProgress.canComplete(completed, total)).isEqualTo(expectedCanComplete);
    }

    @ParameterizedTest(name = "{0}/{1} is rejected")
    @CsvSource({
            "-1, 5",
            "0, -1",
            "6, 5",
            "1, 0",
            "-2147483648, 0",
            "0, -2147483648",
            "2147483647, 2147483646"
    })
    void rejectsInvalidCounts(int completed, int total) {
        assertThatThrownBy(() -> OnboardingProgress.percent(completed, total))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid onboarding progress counts");
        assertThatThrownBy(() -> OnboardingProgress.canComplete(completed, total))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid onboarding progress counts");
    }

    @ParameterizedTest(name = "total={0}")
    @CsvSource({"1", "3", "100", "2147483647"})
    void neverReports100UntilLastItemIsDone(int total) {
        assertThat(OnboardingProgress.percent(total - 1, total)).isLessThan(100);
        assertThat(OnboardingProgress.percent(total, total)).isEqualTo(100);
    }
}
