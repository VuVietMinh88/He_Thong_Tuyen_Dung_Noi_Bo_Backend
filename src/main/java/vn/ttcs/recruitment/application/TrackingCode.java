package vn.ttcs.recruitment.application;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;

// Task 339: the task-329 code is 32 random bytes in Base64URL without padding (43 characters); the stored hash is
// SHA-256 of those bytes, not of the text (PostgresApplicationReceiptWriter.accept). Only the canonical spelling of a
// code is accepted: the last character carries two unused bits, so up to four spellings decode to the same bytes, and
// a lenient decoder would accept all of them.
final class TrackingCode {
    static final String PATTERN = "[A-Za-z0-9_-]{43}";

    private TrackingCode() { }

    static Optional<byte[]> hash(String code) {
        if (code == null || !code.matches(PATTERN)) return Optional.empty();
        byte[] raw = Base64.getUrlDecoder().decode(code);                 // 43 characters always give 32 bytes
        try {
            // Decode, re-encode and compare: refuses the aliases whose unused low bits are not zero.
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(raw).equals(code)) return Optional.empty();
            return Optional.of(MessageDigest.getInstance("SHA-256").digest(raw));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable");
        } finally {
            Arrays.fill(raw, (byte) 0);
        }
    }
}
