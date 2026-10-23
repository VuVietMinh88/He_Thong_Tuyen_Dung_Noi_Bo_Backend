package vn.ttcs.recruitment.offer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class OfferDeclineReasonTest {

    @Test
    void hasExactlyFourCategoriesInOrder() {
        assertThat(OfferDeclineReason.values()).containsExactly(
                OfferDeclineReason.SALARY,
                OfferDeclineReason.LOCATION,
                OfferDeclineReason.OTHER_JOB,
                OfferDeclineReason.PERSONAL);
    }

    @ParameterizedTest
    @CsvSource({
            "SALARY, Lương",
            "LOCATION, Địa điểm",
            "OTHER_JOB, Nhận việc nơi khác",
            "PERSONAL, Lý do cá nhân"
    })
    void parsesExactNameAndExposesLabel(String raw, String expectedLabel) {
        OfferDeclineReason reason = OfferDeclineReason.parseOptional(raw);

        assertThat(reason).isNotNull();
        assertThat(reason.name()).isEqualTo(raw);
        assertThat(reason.label()).isEqualTo(expectedLabel);
    }

    @Test
    void nullMeansNoReason() {
        assertThat(OfferDeclineReason.parseOptional(null)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            " ",
            "   ",
            "\t",
            "\n",
            "\u00A0",
            " SALARY",
            "SALARY ",
            "SALARY\n",
            "\tLOCATION",
            "SAL\u0000ARY",
            "SALARY\u200B",
            "salary",
            "Salary",
            "other_job",
            "Other_Job",
            "OTHER-JOB",
            "OTHERJOB",
            "Lương",
            "UNKNOWN",
            "NONE",
            "0"
    })
    void rejectsInvalidValues(String raw) {
        assertThatThrownBy(() -> OfferDeclineReason.parseOptional(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported offer decline reason");
    }

    @Test
    void exceptionDoesNotEchoInput() {
        String raw = "<script>alert('x')</script>";

        assertThatThrownBy(() -> OfferDeclineReason.parseOptional(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(raw)
                .hasMessageNotContaining("script");
    }
}
