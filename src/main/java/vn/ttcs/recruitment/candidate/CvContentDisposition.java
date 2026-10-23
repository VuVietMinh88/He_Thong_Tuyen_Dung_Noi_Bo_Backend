package vn.ttcs.recruitment.candidate;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;

/**
 * Task 362 (story S4-09): the Content-Disposition header of a CV served by GET /api/v1/applications/{id}/cv, built by
 * hand: {@code inline} for a PDF, {@code attachment} for a DOCX, then {@code filename="<ASCII>"} and
 * {@code filename*=UTF-8''<RFC 5987>}, in that order.
 * <p>
 * The ASCII name comes first because the frontend reads the first match of {@code filename="?([^";]+)} (task 359,
 * candidateProfileService). Spring's ContentDisposition with a charset writes an RFC 2047 {@code filename="=?UTF-8?Q?…"}
 * that this pattern would capture. Browsers that understand {@code filename*} prefer it and show the Vietnamese name.
 * <p>
 * The extension always follows the stored type (V26 allows PDF and DOCX only), never the uploaded name. The ASCII
 * fallback: NFD, combining marks removed, đ to d and Đ to D, every character outside {@code [A-Za-z0-9 ._()-]} to
 * {@code _}, runs of {@code _} collapsed, spaces, dots and {@code _} trimmed at both ends, at most 150 characters with
 * the extension kept; nothing left gives {@code cv.pdf} or {@code cv.docx}. Quotes, semicolons, backslashes and line
 * breaks therefore never reach the header. {@code filename*} percent-encodes every UTF-8 byte of the display name
 * (the stored name with the forced extension) except the RFC 5987 attr-chars.
 */
final class CvContentDisposition {
    static final int MAX_ASCII_LENGTH = 150;
    private static final String ATTR_CHARS = "!#$&+-.^_`|~";

    private CvContentDisposition() { }

    // The two types V26's CHECK application_cvs_valid_type allows. Each switch over it is exhaustive on purpose: a new
    // type fails to compile until it says how it is shown.
    enum StoredType {
        PDF("application/pdf"),
        DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document");

        private final String contentType;

        StoredType(String contentType) {
            this.contentType = contentType;
        }

        String contentType() {
            return contentType;
        }

        // Any other stored type means the schema changed without this mapping: fail loudly, never guess.
        static StoredType of(String contentType) {
            for (StoredType type : values()) {
                if (type.contentType.equals(contentType)) {
                    return type;
                }
            }
            throw new IllegalStateException("Unknown stored CV type");
        }

        // A PDF opens in the browser's viewer; a DOCX is only ever downloaded, never shown inline.
        String dispositionType() {
            return switch (this) {
                case PDF -> "inline";
                case DOCX -> "attachment";
            };
        }

        String extension() {
            return switch (this) {
                case PDF -> ".pdf";
                case DOCX -> ".docx";
            };
        }
    }

    static String header(String storedFilename, String contentType) {
        StoredType type = StoredType.of(contentType);
        return type.dispositionType() + "; filename=\"" + asciiName(storedFilename, type) + "\"; filename*=UTF-8''"
                + encode(displayName(storedFilename, type));
    }

    // The stored name with the stored type's extension: a .pdf or .docx ending (any case) is replaced, any other
    // ending is kept before it; a name without a visible character becomes "cv".
    static String displayName(String storedFilename, StoredType type) {
        String base = withoutKnownExtension(storedFilename == null ? "" : storedFilename.strip());
        if (base.codePoints().noneMatch(CvContentDisposition::visible)) {
            base = "cv";
        }
        return base + type.extension();
    }

    static String asciiName(String storedFilename, StoredType type) {
        String base = withoutKnownExtension(storedFilename == null ? "" : storedFilename);
        String decomposed = Normalizer.normalize(base, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .replace('đ', 'd').replace('Đ', 'D');
        StringBuilder ascii = new StringBuilder();
        decomposed.codePoints().forEach(point -> ascii.append(allowed(point) ? (char) point : '_'));
        String cleaned = trim(ascii.toString().replaceAll("_{2,}", "_"));
        String extension = type.extension();
        if (cleaned.length() > MAX_ASCII_LENGTH - extension.length()) {
            cleaned = trim(cleaned.substring(0, MAX_ASCII_LENGTH - extension.length()));
        }
        return (cleaned.isEmpty() ? "cv" : cleaned) + extension;
    }

    private static String withoutKnownExtension(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String extension : new String[] {".pdf", ".docx"}) {
            if (lower.endsWith(extension)) {
                return name.substring(0, name.length() - extension.length());
            }
        }
        return name;
    }

    private static boolean allowed(int point) {
        return (point >= 'A' && point <= 'Z') || (point >= 'a' && point <= 'z') || (point >= '0' && point <= '9')
                || point == ' ' || point == '.' || point == '_' || point == '(' || point == ')' || point == '-';
    }

    // Spaces, dots and underscores at either end: a name never starts hidden (".x") or ends in a dot or a space,
    // which Windows drops.
    private static String trim(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && " ._".indexOf(value.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && " ._".indexOf(value.charAt(end - 1)) >= 0) {
            end--;
        }
        return value.substring(start, end);
    }

    // RFC 5987 value-chars: attr-char stays, every other UTF-8 byte becomes %XX (upper-case hex).
    static String encode(String value) {
        StringBuilder encoded = new StringBuilder();
        for (byte raw : value.getBytes(StandardCharsets.UTF_8)) {
            int unsigned = raw & 0xFF;
            if ((unsigned >= 'A' && unsigned <= 'Z') || (unsigned >= 'a' && unsigned <= 'z')
                    || (unsigned >= '0' && unsigned <= '9') || ATTR_CHARS.indexOf(unsigned) >= 0) {
                encoded.append((char) unsigned);
            } else {
                encoded.append('%').append(Character.toUpperCase(Character.forDigit(unsigned >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(unsigned & 0xF, 16)));
            }
        }
        return encoded.toString();
    }

    // The house rule for text: a letter, digit, mark, punctuation or symbol is visible; spaces, controls and format
    // characters (ZWSP, BOM, bidi controls) are not.
    private static boolean visible(int point) {
        return !Character.isWhitespace(point) && !Character.isSpaceChar(point) && !Character.isISOControl(point)
                && Character.getType(point) != Character.FORMAT && Character.isDefined(point);
    }
}
