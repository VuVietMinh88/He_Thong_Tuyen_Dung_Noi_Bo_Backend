package vn.ttcs.recruitment.fit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EducationLevelTest {
    @Test
    void aLevelMeetsEveryRequirementAtOrBelowIt() {
        assertThat(EducationLevel.MASTER.meets(EducationLevel.BACHELOR)).isTrue();
        assertThat(EducationLevel.BACHELOR.meets(EducationLevel.BACHELOR)).isTrue();
        assertThat(EducationLevel.COLLEGE.meets(EducationLevel.BACHELOR)).isFalse();
        assertThat(EducationLevel.HIGH_SCHOOL.meets(EducationLevel.DOCTORATE)).isFalse();
    }

    @Test
    void everyLevelHasAVietnameseName() {
        for (EducationLevel level : EducationLevel.values()) {
            String expected = switch (level) {
                case HIGH_SCHOOL -> "Tốt nghiệp THPT";
                case COLLEGE -> "Cao đẳng";
                case BACHELOR -> "Đại học";
                case MASTER -> "Thạc sĩ";
                case DOCTORATE -> "Tiến sĩ";
            };
            assertThat(level.displayName()).isEqualTo(expected);
        }
    }
}
