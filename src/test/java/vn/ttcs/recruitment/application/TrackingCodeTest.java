package vn.ttcs.recruitment.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

// Task 339: only the canonical Base64URL spelling of the task-329 code is a credential, and its hash is SHA-256 of the
// 32 decoded bytes, exactly what PostgresApplicationReceiptWriter stores.
class TrackingCodeTest {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    private static byte[] token() {
        byte[] token = new byte[32];
        new SecureRandom().nextBytes(token);
        return token;
    }

    private static String encode(byte[] token) { return Base64.getUrlEncoder().withoutPadding().encodeToString(token); }

    @Test
    void theHashOfACanonicalCodeIsSha256OfItsBytesNotOfItsText() throws Exception {
        byte[] token = token();
        String code = encode(token);
        assertThat(code).hasSize(43);
        byte[] expected = sha256(token), ofText = sha256(code.getBytes(StandardCharsets.UTF_8));
        assertThat(TrackingCode.hash(code)).hasValueSatisfying(hash -> {
            assertThat(hash).isEqualTo(expected);
            assertThat(hash).isNotEqualTo(ofText);
        });
    }

    @Test
    void theAliasesOfTheLastCharacterAreRefused() {
        // 43 characters carry 258 bits for 256: the last character's two low bits are unused and must be zero.
        byte[] token = token();
        String code = encode(token);
        int last = ALPHABET.indexOf(code.charAt(42));
        assertThat(last % 4).isZero();
        for (int flip = 1; flip < 4; flip++) {
            String alias = code.substring(0, 42) + ALPHABET.charAt(last ^ flip);
            assertThat(Base64.getUrlDecoder().decode(alias)).as("a lenient decoder accepts %s", alias).isEqualTo(token);
            assertThat(TrackingCode.hash(alias)).as("alias %d", flip).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {42, 44})
    void aCodeOfAnotherLengthIsRefused(int length) {
        String code = encode(token());
        String other = length < 43 ? code.substring(0, length) : code + "A";
        assertThat(TrackingCode.hash(other)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"+", "/", "=", " ", "."})
    void aCharacterOutsideBase64UrlIsRefused(String character) {
        String code = encode(token());
        assertThat(TrackingCode.hash(character + code.substring(1))).isEmpty();
    }

    @Test
    void nothingIsNoCode() {
        assertThat(TrackingCode.hash(null)).isEmpty();
        assertThat(TrackingCode.hash("")).isEmpty();
    }

    private static byte[] sha256(byte[] bytes) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(bytes);
    }
}
