package vn.ttcs.recruitment.competency;

// The texts of a framework and of a criterion, as V8 stores them.
// String.strip(), trim() and isBlank() are not enough. PostgreSQL counts the non-breaking spaces U+00A0, U+2007,
// U+202F, which text pasted from Word or a web page often holds, and U+0085, U+180E as spaces ([[:space:]]); Java does
// not call those whitespace. A text made only of zero-width characters such as U+200B or U+FEFF shows nothing either.
final class CompetencyText {
    private CompetencyText() {
    }

    // The optional description: without the spaces around it, and null when nothing visible is left, like a
    // description that was left out. V8 refuses a description that starts or ends with a [[:space:]] character, so such
    // a description failed the CHECK (500); one made only of zero-width characters passed it, yet showed nothing.
    // Spaces and line breaks inside the description are kept.
    static String description(String text) {
        String stripped = stripSpaces(text);
        return stripped == null || stripped.codePoints().noneMatch(CompetencyText::isVisible) ? null : stripped;
    }

    // A code or a name: without any character nobody can see around it, zero-width characters included, and null when
    // nothing visible is left, so @NotBlank refuses it like an empty one. V8 only checks btrim, which removes ASCII
    // spaces, so a code or name made only of such characters was stored and showed nothing, and "HR" followed by one of
    // them was a second code that looks like HR. The characters inside the code or name are kept.
    static String codeOrName(String text) {
        if (text == null) {
            return null;
        }
        int start = 0;
        int end = text.length();
        while (start < end && !isVisible(text.codePointAt(start))) {
            start += Character.charCount(text.codePointAt(start));
        }
        while (end > start && !isVisible(text.codePointBefore(end))) {
            end -= Character.charCount(text.codePointBefore(end));
        }
        return start == end ? null : text.substring(start, end);
    }

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
    // U+FEFF, U+2060).
    private static boolean isVisible(int character) {
        return !Character.isWhitespace(character)
                && !Character.isSpaceChar(character)
                && !Character.isISOControl(character)
                && Character.getType(character) != Character.FORMAT
                && character != 0x0085
                && character != 0x180E;
    }
}
