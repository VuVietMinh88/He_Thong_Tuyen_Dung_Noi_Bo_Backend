package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mail.javamail.JavaMailSender;
import vn.ttcs.recruitment.auth.passwordreset.PasswordResetMailSender;
import vn.ttcs.recruitment.auth.passwordreset.ResetTokenGenerator;

import java.net.URI;
import java.util.Base64;
import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ResetTokenGeneratorTest {

    @Test
    void createsIndependentUrlSafe256BitTokensAndStableHashes() {
        var generator = new ResetTokenGenerator();
        var values = new HashSet<String>();
        for (int index = 0; index < 100; index++) {
            String token = generator.create();
            assertThat(token).matches("[A-Za-z0-9_-]{43}");
            assertThat(Base64.getUrlDecoder().decode(token)).hasSize(32);
            assertThat(values.add(token)).isTrue();
            assertThat(generator.hash(token)).hasSize(64).isNotEqualTo(token)
                    .isEqualTo(generator.hash(token));
        }
        assertThat(generator.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://public.example.test/reset", "//example.test/reset", "/reset-password",
            "https://example.test/reset?next=untrusted", "https://user:password@example.test/reset",
            "https://example.test/reset#token"})
    void rejectsUntrustedResetPageConfiguration(String url) {
        assertThatThrownBy(() -> new PasswordResetMailSender(mock(JavaMailSender.class),
                "no-reply@example.test", URI.create(url))).isInstanceOf(IllegalStateException.class);
    }
}
