package vn.ttcs.recruitment.candidate;

/**
 * Text typed by staff into the candidate-extension APIs (search keywords, saved filter names, skills, titles of
 * interest). "Invisible" is the project's rule (PlainTextValidator.isInvisible, ApplicationSubmission.trim): Java white
 * space, every Unicode space separator (the no-break spaces U+00A0, U+2007, U+202F included) and formatting characters
 * (zero-width spaces and joiners). Java's strip(), isBlank() and \s miss most of them, so a value made only of no-break
 * spaces would otherwise count as typed text, and "Java" followed by a no-break space would never equal "java".
 */
public final class CandidateText {
    private CandidateText() { }

    public static boolean invisible(int point) {
        return Character.isWhitespace(point) || Character.isSpaceChar(point)
                || Character.getType(point) == Character.FORMAT;
    }

    // The value without invisible characters at either end; null when nothing visible is left.
    public static String trim(String value) {
        if (value == null) return null;
        int start = 0;
        int end = value.length();
        while (start < end && invisible(value.codePointAt(start))) start += Character.charCount(value.codePointAt(start));
        while (end > start && invisible(value.codePointBefore(end))) end -= Character.charCount(value.codePointBefore(end));
        return start == end ? null : value.substring(start, end);
    }

    // Every run of white space or space separators as one ordinary space, formatting characters left as they are (a
    // validator decides whether they are allowed). Does not trim.
    public static String collapseSpaces(String value) {
        StringBuilder collapsed = new StringBuilder(value.length());
        boolean space = false;
        for (int index = 0; index < value.length(); ) {
            int point = value.codePointAt(index);
            index += Character.charCount(point);
            if (Character.isWhitespace(point) || Character.isSpaceChar(point)) {
                if (!space) collapsed.append(' ');
                space = true;
            } else {
                collapsed.appendCodePoint(point);
                space = false;
            }
        }
        return collapsed.toString();
    }
}
