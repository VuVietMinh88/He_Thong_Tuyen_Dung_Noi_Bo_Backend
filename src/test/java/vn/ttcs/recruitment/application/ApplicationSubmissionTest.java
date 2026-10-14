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
}
