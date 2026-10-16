package vn.ttcs.recruitment.application;

import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;

@Component
public class ApplicationOutboxCrypto {
    private final ApplicationConfirmationSettings settings;
    private final ObjectMapper json;
    private final SecureRandom random = new SecureRandom();

    public ApplicationOutboxCrypto(ApplicationConfirmationSettings settings, ObjectMapper json) {
        this.settings = settings; this.json = json;
    }

    Encrypted encrypt(UUID outboxId, UUID applicationId, ApplicationConfirmationPayload payload) {
        byte[] plain = json.writeValueAsBytes(payload);
        try {
            if (plain.length > 16368) throw new IllegalStateException("Confirmation payload too large");
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, outboxId, applicationId, settings.keyId(), nonce);
            return new Encrypted(settings.keyId(), nonce, cipher.doFinal(plain));
        } catch (GeneralSecurityException exception) { throw new IllegalStateException("Unable to encrypt confirmation"); }
        finally { Arrays.fill(plain, (byte) 0); }
    }

    ApplicationConfirmationPayload decrypt(ApplicationOutboxRepository.Delivery delivery) {
        byte[] plain = null;
        try {
            if (!settings.keyId().equals(delivery.keyId())) throw new IllegalStateException("Confirmation key unavailable");
            if (delivery.nonce() == null || delivery.nonce().length != 12 || delivery.ciphertext() == null
                    || delivery.ciphertext().length < 16 || delivery.ciphertext().length > 16384) throw new IllegalStateException("Invalid encrypted confirmation");
            plain = cipher(Cipher.DECRYPT_MODE, delivery.id(), delivery.applicationId(), delivery.keyId(), delivery.nonce())
                    .doFinal(delivery.ciphertext());
            var payload = json.readValue(plain, ApplicationConfirmationPayload.class);
            // Validate the receipt again before constructing mail from decrypted data.
            new ApplicationReceipt(delivery.applicationId(), payload.receivedAt(), payload.trackingCode(), payload.trackingUrl());
            if (payload.email() == null || payload.fullName() == null || payload.postingTitle() == null || payload.from() == null) {
                throw new IllegalStateException("Invalid confirmation payload");
            }
            return payload;
        } catch (GeneralSecurityException exception) { throw new IllegalStateException("Invalid encrypted confirmation"); }
        finally { if (plain != null) Arrays.fill(plain, (byte) 0); }
    }

    private Cipher cipher(int mode, UUID outboxId, UUID applicationId, String keyId, byte[] nonce) throws GeneralSecurityException {
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, settings.key(), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(("application-confirmation:v1:"+outboxId+":"+applicationId+":"+keyId).getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    record Encrypted(String keyId, byte[] nonce, byte[] ciphertext) {
        @Override public String toString() { return "Encrypted[redacted]"; }
    }
}
