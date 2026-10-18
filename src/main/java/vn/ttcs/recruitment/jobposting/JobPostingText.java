package vn.ttcs.recruitment.jobposting;

// Tasks 303, 304: text rules shared by the job posting request bodies and the copy of the requisition's texts.
// Task 308: the rule for the comments of the review workflow (invisibleToNull).
final class JobPostingText {
    // PostgreSQL TEXT cannot store the NUL character (code 0, sometimes pasted from other files), so the insert would
    // fail with a 500. Every other character is allowed, including tabs and line breaks. @Pattern skips null.
    // A copy of RequisitionRequest.NO_NUL_CHARACTER, which is private to the requisition package. Since task 304 the
    // posting texts use @PlainText, which refuses NUL as well.
    static final String NO_NUL_CHARACTER = "[^\\x00]*";

    private JobPostingText() {
    }

    // Task 304: Windows (\r\n) and old Mac (\r) line breaks become \n, so the stored text, its length and what the
    // preview shows agree, and @PlainText (which refuses \r) accepts a text pasted from Windows. Indentation and empty
    // lines are kept.
    static String normalizeLineBreaks(String text) {
        return text == null ? null : text.replace("\r\n", "\n").replace('\r', '\n');
    }

    // Task 304: removes every kind of space at both ends, including non-breaking spaces, which trim() keeps, so a title
    // never starts or ends with a space. A copy of the private CompanyProfileRequest.stripSpaces.
    static String stripSpaces(String text) {
        if (text == null) {
            return null;
        }
        int start = 0;
        int end = text.length();
        while (start < end && isSpace(text.charAt(start))) {
            start++;
        }
        while (end > start && isSpace(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    // A blank text counts as not given: V21 stores a text not written yet as NULL, never as blank text, and a blank
    // required text gets only the @NotBlank message.
    static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }

    // Task 308: a workflow comment (the note of a submission, the reviewer's opinion) without any visible character
    // counts as no comment: it is never stored, and where a comment is required JOB_POSTING_COMMENT_REQUIRED answers.
    // A comment with a visible character is kept exactly as sent, line breaks included. blankToNull is not enough:
    // V22 requires a character PostgreSQL does not count as a space ([[:space:]]), and PostgreSQL also counts the
    // non-breaking spaces U+00A0, U+2007, U+202F and U+0085, U+180E as spaces, so a comment made only of them failed
    // the CHECK (500); a comment made only of zero-width characters such as U+200B or U+FEFF passed it and was
    // stored, yet shows nothing. The posting texts keep blankToNull: @PlainText refuses such a text instead of
    // dropping it. The same rule as the requisition workflow's WorkflowComment, which is private to its package.
    static String invisibleToNull(String text) {
        return text == null || text.codePoints().noneMatch(JobPostingText::isVisible) ? null : text;
    }

    private static boolean isSpace(char character) {
        return Character.isWhitespace(character) || Character.isSpaceChar(character);
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
