package vn.ttcs.recruitment.application;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationSubmissionTest {
    @Test
    void normalizesWhitespaceWithoutMergingDifferentEmailAddresses() {
        var input = new ApplicationSubmission("\u200b Nguyễn Văn A\u00a0", " A+tag@Example.test ", "012 345-6789", null, "Hi\nThere");
        assertThat(input.fullName()).isEqualTo("Nguyễn Văn A");
        assertThat(input.normalizedEmail()).isEqualTo("a+tag@example.test");
        assertThat(input.phone()).isEqualTo("0123456789");
        assertThat(input.coverLetter()).isEqualTo("Hi\nThere");
        assertThat(input.toString()).doesNotContain(input.email(), input.fullName(), input.phone());
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(input)).isEmpty();
            assertThat(factory.getValidator().validate(new ApplicationSubmission("\u200b", "bad", "letters", null, "<script>")))
                    .hasSize(4);
        }
    }

    // Task 339: the lookup normalizes a typed email with the very rule the intake stored it with. Special characters
    // are built from code points: NBSP, ZWSP, BOM and the dotted capital I, whose Locale.ROOT lower case is two chars.
    @Test
    void theLookupNormalizesAnEmailExactlyLikeTheStoredApplication() {
        String nbsp = Character.toString(0x00A0), zwsp = Character.toString(0x200B), bom = Character.toString(0xFEFF);
        String dottedI = Character.toString(0x0130);
        for (String email : new String[] {"a@example.test", nbsp + "A@Example.TEST" + zwsp, bom + " a@example.test" + nbsp,
                zwsp + "MIXED.Case+Tag@Example.test", dottedI + "stanbul@example.test"}) {
            var stored = new ApplicationSubmission("Nguyen Van A", email, "0123456789", null, null);
            assertThat(ApplicationSubmission.normalizeEmail(email)).as(email).isEqualTo(stored.normalizedEmail());
        }
        assertThat(ApplicationSubmission.normalizeEmail(dottedI + "@example.test"))
                .isEqualTo("i" + Character.toString(0x0307) + "@example.test");
        assertThat(ApplicationSubmission.normalizeEmail(nbsp + zwsp + bom)).isNull();
        assertThat(ApplicationSubmission.normalizeEmail(null)).isNull();
    }
}
