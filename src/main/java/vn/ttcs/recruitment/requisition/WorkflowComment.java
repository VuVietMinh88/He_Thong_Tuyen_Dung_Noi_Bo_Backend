package vn.ttcs.recruitment.requisition;

// The comments of the approval workflow (the submit note, an approver's opinion, HR's reason for a status change) are
// stored in requisition_workflow_events, whose CHECK (V19) requires a character PostgreSQL does not count as a space
// ([[:space:]]). String.isBlank() is not enough: PostgreSQL also counts the non-breaking spaces U+00A0, U+2007, U+202F
// and U+0085, U+180E as spaces, so a comment made only of them failed the CHECK (500). A comment made only of
// zero-width characters such as U+200B or U+FEFF passed it and was stored, yet shows nothing.
// So a comment without any visible character counts as no comment: it is never stored, and where a comment is
// required the "comment required" error answers. A comment with a visible character is kept exactly as sent,
// line breaks included.
// PlainTextValidator (company profile) has a similar test, but it refuses such a text instead of dropping it and
// keeps the test private to its package.
final class WorkflowComment {
    private WorkflowComment() {
    }

    // The comment, or null when it has no visible character.
    static String orNull(String comment) {
        return comment == null || comment.codePoints().noneMatch(WorkflowComment::isVisible) ? null : comment;
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
