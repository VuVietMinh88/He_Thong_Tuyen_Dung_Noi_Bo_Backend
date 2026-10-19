package vn.ttcs.recruitment.requisition;

// A free text of a requisition request body (a text section, a reason, a note) without any visible character counts
// as not given, like an empty one: it becomes null, and where the text is needed the "missing" error answers.
// String.isBlank() is not enough: the requisition tables store a text that has not been written as NULL, and their
// CHECKs require a character PostgreSQL does not count as a space ([[:space:]]). PostgreSQL also counts the
// non-breaking spaces U+00A0, U+2007, U+202F and U+0085, U+180E as spaces, so a text made only of them failed the
// CHECK (500). A text made only of zero-width characters such as U+200B or U+FEFF passed it and was stored, yet shows
// nothing. A text with a visible character is kept exactly as sent, line breaks and spaces included.
// PlainTextValidator (company profile) has a similar test, but it refuses such a text instead of dropping it and keeps
// the test private to its package.
final class RequisitionText {
    private RequisitionText() {
    }

    // The text, or null when it has no visible character.
    static String invisibleToNull(String text) {
        return text == null || text.codePoints().noneMatch(RequisitionText::isVisible) ? null : text;
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
