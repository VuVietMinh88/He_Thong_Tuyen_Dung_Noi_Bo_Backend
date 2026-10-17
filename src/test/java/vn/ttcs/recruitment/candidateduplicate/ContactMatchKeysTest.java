package vn.ttcs.recruitment.candidateduplicate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.assertThat;

class ContactMatchKeysTest {
    @ParameterizedTest
    @CsvSource({"0912 345 678,0912345678", "0912-345-678,0912345678", "(0912).345.678,0912345678",
            "+84 912 345 678,+84912345678", "+44 (20) 7946-0958,+442079460958"})
    void removesOnlyPhoneSeparators(String input, String expected) {
        assertThat(ContactMatchKeys.from("  One@Example.TEST  ", input))
                .isEqualTo(new ContactMatchKeys("one@example.test", expected));
    }

    @Test void preservesCountryPrefixLeadingZeroAndEmailIdentity() {
        assertThat(ContactMatchKeys.from("one@example.test", "0912345678"))
                .isNotEqualTo(ContactMatchKeys.from("one@example.test", "+84912345678"));
        assertThat(ContactMatchKeys.from("one+job@example.test", "0912345678").email()).isEqualTo("one+job@example.test");
        assertThat(ContactMatchKeys.from("o.ne@example.test", "0912345678").email()).isEqualTo("o.ne@example.test");
        assertThat(ContactMatchKeys.from("İ@example.test", "0912345678").email()).isEqualTo("i\u0307@example.test");
    }

    @Test void diagnosticStringsNeverRevealContacts() {
        assertThat(ContactMatchKeys.from("private@example.test", "0912345678").toString())
                .doesNotContain("private@example.test", "0912345678");
    }
}
