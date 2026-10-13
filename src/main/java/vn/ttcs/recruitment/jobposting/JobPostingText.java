package vn.ttcs.recruitment.jobposting;

// Tasks 303, 304: text rules shared by the job posting request bodies and the copy of the requisition's texts.
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

    private static boolean isSpace(char character) {
        return Character.isWhitespace(character) || Character.isSpaceChar(character);
    }
}
