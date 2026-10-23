package vn.ttcs.recruitment.candidate;

import java.util.Objects;

/**
 * Task 362 (story S4-09): one application's CV as ApplicationCvService hands it to the controller: the stored file
 * name, the stored type (application/pdf or the DOCX type, V26) and the bytes. toString shows neither the name nor the
 * bytes.
 */
public final class CvFile {
    private final String filename;
    private final String contentType;
    private final byte[] content;

    CvFile(String filename, String contentType, byte[] content) {
        this.filename = Objects.requireNonNull(filename, "filename");
        this.contentType = Objects.requireNonNull(contentType, "contentType");
        this.content = Objects.requireNonNull(content, "content");
    }

    public String filename() {
        return filename;
    }

    public String contentType() {
        return contentType;
    }

    // The service hands the array over and keeps no reference, so it is not copied again (up to 10 MiB).
    public byte[] content() {
        return content;
    }

    @Override
    public String toString() {
        return "CvFile[filename and content redacted, contentType=" + contentType + ", size=" + content.length + "]";
    }
}
