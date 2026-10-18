package vn.ttcs.recruitment.competency;

// The optional description of a framework or of a criterion, as V8 stores it: without the spaces around it, and
// null when nothing visible is left, like a description that was left out.
// String.strip() and isBlank() are not enough: V8 refuses a description that starts or ends with a character
// PostgreSQL counts as a space ([[:space:]]). PostgreSQL also counts the non-breaking spaces U+00A0, U+2007, U+202F,
// which text pasted from Word or a web page often ends with, and U+0085, U+180E. Java does not call those whitespace,
// so such a description failed the CHECK (500). A description made only of zero-width characters such as U+200B or
// U+FEFF passed the CHECK, yet showed nothing. Spaces and line breaks inside the description are kept.
final class CompetencyText {
    private CompetencyText() {
    }

    static String description(String text) {
        String stripped = stripSpaces(text);
        return stripped == null || stripped.codePoints().noneMatch(CompetencyText::isVisible) ? null : stripped;
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
