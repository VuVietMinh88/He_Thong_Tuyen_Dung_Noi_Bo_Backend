package vn.ttcs.recruitment.account.lock;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AccountLockRequest(
        @NotBlank(message = "Lý do khóa không được để trống.")
        @Size(max = 500, message = "Lý do khóa tối đa 500 ký tự.") String reason) {
    // The spaces around the reason are removed. A reason without a visible character becomes null, so @NotBlank
    // refuses it like an empty one.
    public AccountLockRequest {
        reason = stripSpaces(reason);
        reason = reason == null || reason.codePoints().noneMatch(AccountLockRequest::isVisible) ? null : reason;
    }

    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unsupported account lock field");
    }

    // String.strip() is not enough: V6 refuses a reason that starts or ends with a character PostgreSQL counts as a
    // space ([[:space:]]). PostgreSQL also counts the non-breaking spaces U+00A0, U+2007, U+202F, which text pasted
    // from Word or a web page often ends with, and U+0085, U+180E. Java does not call those whitespace, so such a
    // reason failed the CHECK (500). They are removed here too. Spaces inside the reason are kept.
    private static String stripSpaces(String value) {
        if (value == null) {
            return null;
        }
        int start = 0;
        int end = value.length();
        while (start < end && isSpace(value.charAt(start))) {
            start++;
        }
        while (end > start && isSpace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    // Every space and line break of Java (isWhitespace), every Unicode space including the non-breaking ones
    // (isSpaceChar), and two more characters PostgreSQL counts as spaces: U+0085 (next line) and U+180E.
    private static boolean isSpace(char character) {
        return Character.isWhitespace(character) || Character.isSpaceChar(character)
                || character == 0x0085 || character == 0x180E;
    }

    // Not visible: the spaces above, control characters and format characters (Unicode type Cf, for example U+200B,
    // U+FEFF, U+2060). A reason made only of zero-width characters passed the CHECK, yet showed nothing.
    private static boolean isVisible(int character) {
        return !Character.isWhitespace(character)
                && !Character.isSpaceChar(character)
                && !Character.isISOControl(character)
                && Character.getType(character) != Character.FORMAT
                && character != 0x0085
                && character != 0x180E;
    }
}
