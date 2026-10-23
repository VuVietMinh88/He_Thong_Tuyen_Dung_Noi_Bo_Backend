package vn.ttcs.recruitment.invitation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import vn.ttcs.recruitment.common.ApiException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** No database, file credentials or SMTP: validates the queued invitation's authenticated encryption boundary. */
class InterviewInvitationCryptoTest {
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String TOKEN = "AbCdEf0123456789_-".repeat(3).substring(0, 43);
    private static final String PAGE = "https://portal.example.test/api/v1/public/interview-invitations/reply";

    private InterviewInvitationSettings settings(String page, String from, String key, String keyId) {
        return new InterviewInvitationSettings(true, false, URI.create(page), from, keyId, key, "Asia/Ho_Chi_Minh");
    }

    private InterviewInvitationSettings settings() { return settings(PAGE, "support@example.test", KEY, "v1"); }

    private InterviewMailPayload payload() {
        return new InterviewMailPayload("candidate@example.test", "Nguyễn Văn A", "Java Developer", "Vòng chuyên môn",
                Instant.parse("2026-11-01T03:00:00Z"), Instant.parse("2026-11-01T04:00:00Z"), "ONSITE",
                "Phòng A", null, "Chuẩn bị CV và kinh nghiệm dự án.", "INVITATION", settings().link(TOKEN), null);
    }

    private InterviewInvitationCrypto crypto(InterviewInvitationSettings settings) {
        return new InterviewInvitationCrypto(settings, JsonMapper.builder().build());
    }

    @ParameterizedTest @ValueSource(strings = {"https://portal.example.test/reply", "http://localhost:8080/reply",
            "http://127.0.0.1:8080/reply", "http://[::1]:8080/reply"})
    void linksUseOnlyTheConfiguredHttpsOrLoopbackPageAndKeepTokensInFragments(String page) {
        var settings = settings(page, "support@example.test", KEY, "v1");
        URI link = URI.create(settings.link(TOKEN));
        assertThat(link.getQuery()).isNull();
        assertThat(link.getFragment()).isEqualTo("token=" + TOKEN);
        assertThat(settings.link(TOKEN)).isEqualTo(page + "#token=" + TOKEN);
    }

    @ParameterizedTest @ValueSource(strings = {"http://outside.example.test/reply", "https://user:pass@portal.test/reply",
            "https://portal.test/reply?token=x", "https://portal.test/reply#token=x", "/reply",
            "ftp://portal.test/reply", "javascript:alert(1)", "https:///reply"})
    void unsafeReplyPageStopsStartup(String page) {
        assertThatThrownBy(() -> settings(page, "support@example.test", KEY, "v1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"", "not-an-email", "a@example.test,b@example.test",
            "a@example.test\r\nBcc: private@example.test"})
    void malformedOrMultipleSenderAddressesAreRejectedWithoutEcho(String from) {
        assertThatThrownBy(() -> settings(PAGE, from, KEY, "v1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("MAIL_FROM must contain one valid mailbox");
    }

    @ParameterizedTest @ValueSource(strings = {"invalid-base64", "AAAA", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
    void malformedOrWrongSizedKeysStopStartup(String key) {
        assertThatThrownBy(() -> settings(PAGE, "support@example.test", key, "v1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"", "bad key", "v1:other", "123456789012345678901234567890123" + "45"})
    void invalidKeyIdentifierStopsStartup(String keyId) {
        assertThatThrownBy(() -> settings(PAGE, "support@example.test", KEY, keyId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"", "short", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA?"})
    void linkCannotCarryArbitraryQueryOrFragmentSyntax(String token) {
        assertThatThrownBy(() -> settings().link(token)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void missingKeyOrDisabledFeatureNeverCreatesAUsableStartupSecret() {
        var unconfigured = settings(PAGE, "support@example.test", "", "v1");
        assertThat(unconfigured.ready()).isFalse();
        assertThatThrownBy(unconfigured::requireReady).isInstanceOfSatisfying(ApiException.class,
                failure -> assertThat(failure.getStatus().value()).isEqualTo(503));
        assertThatThrownBy(unconfigured::key).isInstanceOf(ApiException.class);
        var disabled = new InterviewInvitationSettings(false, true, URI.create(PAGE), "support@example.test", "v1",
                KEY, "Asia/Ho_Chi_Minh");
        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.ready()).isFalse();
        assertThatThrownBy(() -> crypto(disabled).encrypt(UUID.randomUUID(), UUID.randomUUID(), payload()))
                .isInstanceOf(ApiException.class);
        assertThat(settings().ready()).isTrue();
        assertThat(settings().deliveryEnabled()).isFalse();
    }

    @Test void roundTripUsesFreshNoncesAndDiagnosticsNeverContainRecipientOrToken() {
        var crypto = crypto(settings());
        UUID outbox = UUID.randomUUID(), interview = UUID.randomUUID();
        var first = crypto.encrypt(outbox, interview, payload());
        var second = crypto.encrypt(outbox, interview, payload());
        assertThat(first.nonce()).hasSize(12).isNotEqualTo(second.nonce());
        assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
        assertThat(new String(first.ciphertext(), StandardCharsets.ISO_8859_1))
                .doesNotContain(TOKEN, "candidate@example.test");
        assertThat(crypto.decrypt(outbox, interview, first.keyId(), first.nonce(), first.ciphertext())).isEqualTo(payload());
        var delivery = new InterviewInvitationOutboxRepository.Delivery(outbox, interview, UUID.randomUUID(), 0,
                UUID.randomUUID(), null, "INVITATION", first.keyId(), first.nonce(), first.ciphertext(), UUID.randomUUID(), 1);
        String diagnostics = settings() + " " + payload() + " " + first + " " + delivery;
        assertThat(diagnostics).doesNotContain(TOKEN, KEY, "candidate@example.test", "Nguyễn Văn A", "Private note");
    }

    @Test void ciphertextCannotBeMovedBetweenOutboxRowsInterviewsOrKeyVersions() {
        var crypto = crypto(settings());
        UUID outbox = UUID.randomUUID(), interview = UUID.randomUUID();
        var encrypted = crypto.encrypt(outbox, interview, payload());
        assertThatThrownBy(() -> crypto.decrypt(UUID.randomUUID(), interview, encrypted.keyId(), encrypted.nonce(), encrypted.ciphertext()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> crypto.decrypt(outbox, UUID.randomUUID(), encrypted.keyId(), encrypted.nonce(), encrypted.ciphertext()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> crypto.decrypt(outbox, interview, "v2", encrypted.nonce(), encrypted.ciphertext()))
                .isInstanceOf(IllegalStateException.class);
        // Even when v2 is a configured key using identical bytes, changing only the identifier breaks the AAD.
        var otherVersion = crypto(settings(PAGE, "support@example.test", KEY, "v2"));
        assertThatThrownBy(() -> otherVersion.decrypt(outbox, interview, "v2", encrypted.nonce(), encrypted.ciphertext()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void changedCiphertextNonceOrKeyIsRefused() {
        var crypto = crypto(settings());
        UUID outbox = UUID.randomUUID(), interview = UUID.randomUUID();
        var encrypted = crypto.encrypt(outbox, interview, payload());
        byte[] ciphertext = encrypted.ciphertext().clone(); ciphertext[0] ^= 1;
        assertThatThrownBy(() -> crypto.decrypt(outbox, interview, "v1", encrypted.nonce(), ciphertext))
                .isInstanceOf(IllegalStateException.class).hasMessage("Invalid encrypted invitation");
        byte[] nonce = encrypted.nonce().clone(); nonce[0] ^= 1;
        assertThatThrownBy(() -> crypto.decrypt(outbox, interview, "v1", nonce, encrypted.ciphertext()))
                .isInstanceOf(IllegalStateException.class);
        byte[] key = new byte[32]; Arrays.fill(key, (byte) 1);
        var wrongKey = crypto(settings(PAGE, "support@example.test", Base64.getEncoder().encodeToString(key), "v1"));
        assertThatThrownBy(() -> wrongKey.decrypt(outbox, interview, "v1", encrypted.nonce(), encrypted.ciphertext()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void corruptPayloadBoundsAreRefusedBeforeAttemptingDeserialization() {
        var crypto = crypto(settings());
        UUID outbox = UUID.randomUUID(), interview = UUID.randomUUID();
        var encrypted = crypto.encrypt(outbox, interview, payload());
        for (byte[] nonce : new byte[][] {null, new byte[0], new byte[11], new byte[13]}) {
            assertThatThrownBy(() -> crypto.decrypt(outbox, interview, "v1", nonce, encrypted.ciphertext()))
                    .isInstanceOf(IllegalStateException.class);
        }
        for (byte[] ciphertext : new byte[][] {null, new byte[0], new byte[15], new byte[32769]}) {
            assertThatThrownBy(() -> crypto.decrypt(outbox, interview, "v1", encrypted.nonce(), ciphertext))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
