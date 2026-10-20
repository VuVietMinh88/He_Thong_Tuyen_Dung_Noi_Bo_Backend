package vn.ttcs.recruitment.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ManualApplicationDuplicatesTest {
    @Test
    void phonesAreComparedInTheirNationalForm() {
        assertThat(ManualApplicationDuplicates.nationalPhone("+84912345678")).isEqualTo("0912345678");
        assertThat(ManualApplicationDuplicates.nationalPhone("0912345678")).isEqualTo("0912345678");
        assertThat(ManualApplicationDuplicates.nationalPhone("+6591234567")).isEqualTo("+6591234567");
        // The storage removes separators first, so the check sees what would be stored.
        var submission = new ApplicationSubmission("x", "a@example.test", "+84 (912) 345-678", null, null);
        assertThat(ManualApplicationDuplicates.nationalPhone(submission.phone())).isEqualTo("0912345678");
    }
}
