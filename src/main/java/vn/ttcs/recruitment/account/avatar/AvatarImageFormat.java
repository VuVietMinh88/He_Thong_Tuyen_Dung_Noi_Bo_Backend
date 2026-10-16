package vn.ttcs.recruitment.account.avatar;

import java.util.Arrays;
import java.util.Optional;

/**
 * The two accepted avatar formats. A format is recognised only by the first bytes of the file ("magic bytes");
 * the file name and the Content-Type sent by the browser are ignored because anyone can rename a file.
 */
public enum AvatarImageFormat {
    JPEG("jpeg", 0xFF, 0xD8, 0xFF),
    PNG("png", 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n');

    private final String readerName;
    private final byte[] signature;

    AvatarImageFormat(String readerName, int... signature) {
        this.readerName = readerName;
        this.signature = new byte[signature.length];
        for (int index = 0; index < signature.length; index++) {
            this.signature[index] = (byte) signature[index];
        }
    }

    /** Format name understood by {@code ImageIO.getImageReadersByFormatName}. */
    String readerName() {
        return readerName;
    }

    static Optional<AvatarImageFormat> detect(byte[] content) {
        return Arrays.stream(values()).filter(format -> format.startsWithSignature(content)).findFirst();
    }

    private boolean startsWithSignature(byte[] content) {
        return content.length >= signature.length
                && Arrays.equals(content, 0, signature.length, signature, 0, signature.length);
    }
}
