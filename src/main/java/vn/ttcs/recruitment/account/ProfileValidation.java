package vn.ttcs.recruitment.account;

public final class ProfileValidation {
    public static final String VIETNAM_PHONE_PATTERN = "(?:0[35789][0-9]{8}|02[0-9]{9})";

    private ProfileValidation() { }

    // A required text such as the full name, as optionalText cleans it. A text without a visible character becomes
    // null, so @NotBlank refuses it like an empty one.
    public static String requiredText(String value) {
        return optionalText(value);
    }

    // The text without the characters nobody can see around it, or null when no visible character is left, so an
    // optional field is cleared like an empty one. String.trim() was not enough: it keeps the non-breaking spaces
    // U+00A0, U+2007, U+202F, which text pasted from Word or a web page often holds, U+0085, U+180E, U+3000 and
    // zero-width characters such as U+200B or U+FEFF, and @NotBlank does not count most of them as blank either. A name
    // or title made only of them was stored and showed nothing. The characters inside the text are kept.
    public static String optionalText(String value) {
        if (value == null) { return null; }
        int start = 0;
        int end = value.length();
        while (start < end && !isVisible(value.codePointAt(start))) {
            start += Character.charCount(value.codePointAt(start));
        }
        while (end > start && !isVisible(value.codePointBefore(end))) {
            end -= Character.charCount(value.codePointBefore(end));
        }
        return start == end ? null : value.substring(start, end);
    }

    public static String normalizePhone(String value) {
        String phone = optionalText(value);
        if (phone != null && phone.startsWith("+84")) {
            return "0" + phone.substring(3);
        }
        return phone;
    }

    // Not visible: every space and line break of Java (isWhitespace) and of Unicode, the non-breaking ones included
    // (isSpaceChar), control characters, format characters (Unicode type Cf, for example U+200B, U+FEFF, U+2060), and
    // U+0085, U+180E, which PostgreSQL counts as spaces.
    private static boolean isVisible(int character) {
        return !Character.isWhitespace(character)
                && !Character.isSpaceChar(character)
                && !Character.isISOControl(character)
                && Character.getType(character) != Character.FORMAT
                && character != 0x0085
                && character != 0x180E;
    }
}
