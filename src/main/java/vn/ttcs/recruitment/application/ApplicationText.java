package vn.ttcs.recruitment.application;

// Task 345 (story S4-06): the text rules of the candidate-facing free texts (the withdrawal reason; from task 350 the
// referral note). Exact copies of JobPostingText.normalizeLineBreaks and JobPostingText.invisibleToNull: the house
// copies these rules per package on purpose (JobPostingText says so of RequisitionRequest and CompanyProfileRequest),
// because the originals are private to their packages.
final class ApplicationText {
    private ApplicationText() {
    }

    // Windows (\r\n) and old Mac (\r) line breaks become \n, so the stored text, its length and what a reader sees agree,
    // and @PlainText (which refuses \r) accepts a text pasted from Windows. Indentation and empty lines are kept.
    static String normalizeLineBreaks(String text) {
        return text == null ? null : text.replace("\r\n", "\n").replace('\r', '\n');
    }

    // A text without any visible character counts as no text: it is never stored. A text with a visible character is
    // kept exactly as sent, line breaks included. blankToNull is not enough: V34 requires a character PostgreSQL does
    // not count as a space ([[:space:]]), and PostgreSQL also counts the non-breaking spaces U+00A0, U+2007, U+202F and
    // U+0085, U+180E as spaces, so a text made only of them would fail the CHECK (500); a text made only of zero-width
    // characters such as U+200B or U+FEFF would pass it and be stored, yet show nothing.
    static String invisibleToNull(String text) {
        return text == null || text.codePoints().noneMatch(ApplicationText::isVisible) ? null : text;
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
