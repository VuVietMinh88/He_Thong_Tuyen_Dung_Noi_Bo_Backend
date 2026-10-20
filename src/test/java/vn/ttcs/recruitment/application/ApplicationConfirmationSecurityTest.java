package vn.ttcs.recruitment.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import tools.jackson.databind.json.JsonMapper;
import vn.ttcs.recruitment.common.ApiException;

import java.net.URI;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class ApplicationConfirmationSecurityTest {
    static final String KEY=Base64.getEncoder().encodeToString(new byte[32]);
    private ApplicationConfirmationSettings settings(String page,String from,String key,String id) {
        return new ApplicationConfirmationSettings(URI.create(page),from,key,id);
    }
    private ApplicationConfirmationSettings settings(){return settings("https://portal.example.test/tra-cuu","support@example.test",KEY,"v1");}
    private ApplicationConfirmationPayload payload(){return new ApplicationConfirmationPayload("candidate@example.test","Nguyễn Văn A",
            "Developer",Instant.parse("2026-10-14T03:00:00Z"),"A".repeat(43),"https://portal.example.test/tra-cuu#ma="+"A".repeat(43),"support@example.test");}
    private ApplicationOutboxRepository.Delivery delivery(UUID id,UUID app,ApplicationOutboxCrypto.Encrypted encrypted) {
        return new ApplicationOutboxRepository.Delivery(id,app,"<test@example.test>",encrypted.keyId(),encrypted.nonce(),encrypted.ciphertext(),UUID.randomUUID(),1);
    }
    @ParameterizedTest @ValueSource(strings={"http://evil.test/track","https://user:pass@portal.test/track",
            "https://portal.test/track?ma=x","https://portal.test/track#ma=x","/relative","ftp://portal.test/track"})
    void invalidTrackingUrlsStopStartup(String url) {
        assertThatThrownBy(() -> settings(url,"support@example.test",KEY,"v1")).isInstanceOf(IllegalArgumentException.class);
    }
    @ParameterizedTest @ValueSource(strings={"http://localhost:5173/tra-cuu","http://127.0.0.1:5173/tra-cuu","http://[::1]:5173/tra-cuu","https://portal.test/tra-cuu"})
    void fixedHttpsAndLoopbackUrlsAreAllowed(String url) {
        assertThat(settings(url,"support@example.test",KEY,"v1").trackingUrl("A".repeat(43))).isEqualTo(url+"#ma="+"A".repeat(43));
    }
    @ParameterizedTest @ValueSource(strings={"","not-an-email","a@example.test,b@example.test","a@example.test\r\nBcc: evil@example.test"})
    void invalidFromDoesNotDiscloseConfigurationInItsError(String from) {
        assertThatThrownBy(() -> settings("https://portal.test/track",from,KEY,"v1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("MAIL_FROM must contain one valid mailbox");
    }
    @ParameterizedTest @ValueSource(strings={"not-base64","AAAA","AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
    void malformedOrWrongSizedKeysStopStartup(String key) {
        assertThatThrownBy(() -> settings("https://portal.test/track","support@example.test",key,"v1"))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @ParameterizedTest @ValueSource(strings={"","bad key","a"+"1234567890123456789012345678901234"})
    void invalidKeyIdsStopStartup(String id) {
        assertThatThrownBy(() -> settings("https://portal.test/track","support@example.test",KEY,id)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void missingKeyIsNotReadyAndCannotOpenIntake() {
        var settings=settings("http://localhost:5173/tra-cuu","support@example.test","","v1");
        assertThat(settings.ready()).isFalse();assertThatThrownBy(settings::key).isInstanceOf(IllegalStateException.class);
        var beans=new DefaultListableBeanFactory();beans.registerSingleton("quota",(ApplicationAttemptQuota)request -> {});
        beans.registerSingleton("writer",new ApplicationReceiptWriter(){
            public boolean ready(){return false;}
            public ApplicationReceipt accept(UUID id,ApplicationSubmission submission,ValidatedCv cv){throw new AssertionError();}
        });
        var availability=new ApplicationIntakeAvailability(true,beans.getBeanProvider(ApplicationAttemptQuota.class),beans.getBeanProvider(ApplicationReceiptWriter.class));
        assertThatThrownBy(availability::requireReady).isInstanceOf(ApiException.class);
    }
    @Test void encryptsRoundTripWithFreshNonceAndRedactsAllDiagnosticStrings() {
        var crypto=new ApplicationOutboxCrypto(settings(),JsonMapper.builder().build());UUID id=UUID.randomUUID(),app=UUID.randomUUID();
        var a=crypto.encrypt(id,app,payload());var b=crypto.encrypt(id,app,payload());
        assertThat(a.nonce()).hasSize(12).isNotEqualTo(b.nonce());assertThat(a.ciphertext()).isNotEqualTo(b.ciphertext());
        assertThat(crypto.decrypt(delivery(id,app,a))).isEqualTo(payload());
        assertThat(settings().toString()+payload()+a+delivery(id,app,a)).doesNotContain("candidate@example.test","Nguyễn","A".repeat(43),KEY);
    }
    @Test void rejectsCiphertextTamperingRowSwappingWrongKeyAndUnknownKeyId() {
        var crypto=new ApplicationOutboxCrypto(settings(),JsonMapper.builder().build());UUID id=UUID.randomUUID(),app=UUID.randomUUID();
        var encrypted=crypto.encrypt(id,app,payload());
        assertThatThrownBy(() -> crypto.decrypt(delivery(UUID.randomUUID(),app,encrypted))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> crypto.decrypt(delivery(id,UUID.randomUUID(),encrypted))).isInstanceOf(IllegalStateException.class);
        var changed=encrypted.ciphertext().clone();changed[0]^=1;
        assertThatThrownBy(() -> crypto.decrypt(delivery(id,app,new ApplicationOutboxCrypto.Encrypted("v1",encrypted.nonce(),changed))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> crypto.decrypt(delivery(id,app,new ApplicationOutboxCrypto.Encrypted("v2",encrypted.nonce(),encrypted.ciphertext()))))
                .isInstanceOf(IllegalStateException.class);
        byte[] other=new byte[32];java.util.Arrays.fill(other,(byte)1);
        var wrong=new ApplicationOutboxCrypto(settings("https://portal.test/track","support@example.test",Base64.getEncoder().encodeToString(other),"v1"),JsonMapper.builder().build());
        assertThatThrownBy(() -> wrong.decrypt(delivery(id,app,encrypted))).isInstanceOf(IllegalStateException.class);
    }
    @Test void invalidReceiptAndOversizedPayloadCannotBeDelivered() {
        var crypto=new ApplicationOutboxCrypto(settings(),JsonMapper.builder().build());UUID id=UUID.randomUUID(),app=UUID.randomUUID();
        var invalid=new ApplicationConfirmationPayload("a@example.test","A","Title",Instant.now(),"bad","https://portal.test/#ma=bad","support@example.test");
        assertThatThrownBy(() -> crypto.decrypt(delivery(id,app,crypto.encrypt(id,app,invalid)))).isInstanceOf(IllegalArgumentException.class);
        var huge=new ApplicationConfirmationPayload("a@example.test","a".repeat(20000),"Title",Instant.now(),"A".repeat(43),payload().trackingUrl(),"support@example.test");
        assertThatThrownBy(() -> crypto.encrypt(id,app,huge)).isInstanceOf(IllegalStateException.class);
    }
}
