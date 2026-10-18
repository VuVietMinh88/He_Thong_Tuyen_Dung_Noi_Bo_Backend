package vn.ttcs.recruitment.invitation;

import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;

/** Each payload is authenticated to its outbox row, interview and key version. */
@Component
public class InterviewInvitationCrypto {
    private final InterviewInvitationSettings settings;
    private final ObjectMapper json;
    private final SecureRandom random = new SecureRandom();
    public InterviewInvitationCrypto(InterviewInvitationSettings settings, ObjectMapper json) {
        this.settings = settings; this.json = json;
    }
    public Encrypted encrypt(UUID outboxId, UUID interviewId, InterviewMailPayload payload) {
        settings.requireReady();
        byte[] plain = json.writeValueAsBytes(payload);
        try {
            if (plain.length > 32752) throw new IllegalStateException("Invitation payload too large");
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            return new Encrypted(settings.keyId(), nonce,
                    cipher(Cipher.ENCRYPT_MODE, outboxId, interviewId, settings.keyId(), nonce).doFinal(plain));
        } catch (GeneralSecurityException failure) { throw new IllegalStateException("Unable to encrypt invitation"); }
        finally { Arrays.fill(plain, (byte) 0); }
    }
    public InterviewMailPayload decrypt(UUID outboxId, UUID interviewId, String keyId, byte[] nonce, byte[] ciphertext) {
        byte[] plain = null;
        try {
            if (!settings.keyId().equals(keyId) || nonce == null || nonce.length != 12
                    || ciphertext == null || ciphertext.length < 16 || ciphertext.length > 32768) {
                throw new IllegalStateException("Invalid encrypted invitation");
            }
            plain = cipher(Cipher.DECRYPT_MODE, outboxId, interviewId, keyId, nonce).doFinal(ciphertext);
            return json.readValue(plain, InterviewMailPayload.class);
        } catch (GeneralSecurityException failure) { throw new IllegalStateException("Invalid encrypted invitation"); }
        finally { if (plain != null) Arrays.fill(plain, (byte) 0); }
    }
    private Cipher cipher(int mode, UUID outboxId, UUID interviewId, String keyId, byte[] nonce)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, settings.key(), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(("interview-invitation:v1:" + outboxId + ":" + interviewId + ":" + keyId).getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
    public record Encrypted(String keyId, byte[] nonce, byte[] ciphertext) {
        @Override public String toString() { return "Encrypted[redacted]"; }
    }
}
