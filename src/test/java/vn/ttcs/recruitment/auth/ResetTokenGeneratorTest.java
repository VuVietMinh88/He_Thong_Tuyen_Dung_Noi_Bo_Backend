package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.Test;
import vn.ttcs.recruitment.auth.passwordreset.ResetTokenGenerator;

import java.util.Base64;
import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;

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

}
