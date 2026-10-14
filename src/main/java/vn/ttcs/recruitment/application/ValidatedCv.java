package vn.ttcs.recruitment.application;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

// Only CvValidator constructs this value. Defensive copies prevent changing bytes after inspection.
public final class ValidatedCv {
    private final String filename;
    private final String contentType;
    private final byte[] content;
    private final byte[] sha256;

    ValidatedCv(String filename, String contentType, byte[] content) {
        this.filename = filename;
        this.contentType = contentType;
        this.content = content.clone();
        try {
            this.sha256 = MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    public String filename() { return filename; }
    public String contentType() { return contentType; }
    public int sizeBytes() { return content.length; }
    public byte[] content() { return content.clone(); }
    public byte[] sha256() { return sha256.clone(); }
    @Override public String toString() { return "ValidatedCv[content and filename redacted, size=" + content.length + "]"; }
}
