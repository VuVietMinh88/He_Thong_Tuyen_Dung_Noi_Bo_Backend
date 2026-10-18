package vn.ttcs.recruitment.offerdelivery;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import vn.ttcs.recruitment.offer.OfferMailPayload;
import vn.ttcs.recruitment.common.ApiException;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OfferDeliveryCryptoTest {
    private static final String KEY="AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private final OfferDeliverySettings settings=new OfferDeliverySettings(true,false,URI.create("https://portal.example.test/reply"),
            "support@example.test","v1",KEY,"Asia/Ho_Chi_Minh");
    private final OfferMailPayload payload=new OfferMailPayload("candidate@example.test","Nguyễn A","Java",20000000,2000000,
            LocalDate.of(2030,2,1),Instant.parse("2030-01-01T00:00:00Z"),settings.link("a".repeat(43)));
    private final OfferDeliveryCrypto crypto=new OfferDeliveryCrypto(settings,JsonMapper.builder().build());
    @Test void roundTripUsesFreshNoncesAndRedactsEveryDiagnostic() {
        UUID row=UUID.randomUUID(),offer=UUID.randomUUID();
        var first=crypto.encrypt(row,offer,payload); var second=crypto.encrypt(row,offer,payload);
        assertThat(first.nonce()).hasSize(12).isNotEqualTo(second.nonce());
        assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
        assertThat(crypto.decrypt(row,offer,first.keyId(),first.nonce(),first.ciphertext())).isEqualTo(payload);
        assertThat(settings+" "+payload+" "+first).doesNotContain("candidate@example.test",KEY,"a".repeat(43),"Nguyễn A");
        assertThat(URI.create(payload.replyUrl()).getQuery()).isNull();
    }
    @Test void ciphertextIsBoundToOutboxOfferAndKeyVersionAndTamperingIsRejected() {
        UUID row=UUID.randomUUID(),offer=UUID.randomUUID(); var encrypted=crypto.encrypt(row,offer,payload);
        assertThatThrownBy(() -> crypto.decrypt(UUID.randomUUID(),offer,"v1",encrypted.nonce(),encrypted.ciphertext())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> crypto.decrypt(row,UUID.randomUUID(),"v1",encrypted.nonce(),encrypted.ciphertext())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> crypto.decrypt(row,offer,"v2",encrypted.nonce(),encrypted.ciphertext())).isInstanceOf(IllegalStateException.class);
        byte[] altered=encrypted.ciphertext().clone(); altered[0]^=1;
        assertThatThrownBy(() -> crypto.decrypt(row,offer,"v1",encrypted.nonce(),altered)).isInstanceOf(IllegalStateException.class);
    }
    @Test void disabledOrUnconfiguredFeatureCannotEncryptOrCreateStartupSecret() {
        var missing=new OfferDeliverySettings(true,false,URI.create("https://portal.example.test/reply"),"support@example.test","v1","","Asia/Ho_Chi_Minh");
        assertThat(missing.ready()).isFalse(); assertThatThrownBy(missing::requireReady).isInstanceOf(ApiException.class);
        var disabled=new OfferDeliverySettings(false,true,URI.create("https://portal.example.test/reply"),"support@example.test","v1",KEY,"Asia/Ho_Chi_Minh");
        assertThat(disabled.ready()).isFalse(); assertThatThrownBy(disabled::key).isInstanceOf(ApiException.class);
    }
    @Test void unsafeReplyUrlOrSenderOrKeyIsRejected() {
        for(String uri:java.util.List.of("http://external.test/reply","https://user:pass@portal.test/reply","https://portal.test/reply?token=x","https://portal.test/reply#x")) {
            assertThatThrownBy(() -> new OfferDeliverySettings(true,false,URI.create(uri),"support@example.test","v1",KEY,"Asia/Ho_Chi_Minh"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new OfferDeliverySettings(true,false,URI.create("https://portal.test/reply"),"a@test.test,b@test.test","v1",KEY,"Asia/Ho_Chi_Minh")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OfferDeliverySettings(true,false,URI.create("https://portal.test/reply"),"a@test.test","v1","AAAA","Asia/Ho_Chi_Minh")).isInstanceOf(IllegalArgumentException.class);
    }
}
