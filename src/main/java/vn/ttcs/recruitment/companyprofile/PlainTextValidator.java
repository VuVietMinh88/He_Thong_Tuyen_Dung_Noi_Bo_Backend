package vn.ttcs.recruitment.companyprofile;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

// Keeps HTML out of the company page, so a saved text can never run as a script in a candidate's browser.
// A browser only starts a tag, comment or declaration when '<' is directly followed by an ASCII letter,
// '/', '!' or '?'. Exactly that is refused, so "lương < 20 triệu" or "<3" are still accepted.
// Also refused, so the public page shows the text exactly as it is stored:
// - control characters (for example NUL, which PostgreSQL cannot store); a multiline text may use \n and tab;
// - the Unicode line and paragraph separators U+2028, U+2029: the only line break that is stored is \n;
// - bidi embedding, override and isolate characters, which make text display in another order than typed;
// - a text without any visible character, for example only zero-width spaces (U+200B): the page would look empty.
public class PlainTextValidator implements ConstraintValidator<PlainText, String> {
    private boolean multiline;

    @Override
    public void initialize(PlainText annotation) {
        multiline = annotation.multiline();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // A missing or empty text is reported once, by @NotBlank on the required fields.
        if (value == null || value.isEmpty()) {
            return true;
        }
        int[] characters = value.codePoints().toArray();
        boolean hasVisibleCharacter = false;
        for (int index = 0; index < characters.length; index++) {
            int current = characters[index];
            if (current == '<' && index + 1 < characters.length && startsHtml(characters[index + 1])) {
                return false;
            }
            if (isRefused(current)) {
                return false;
            }
            if (!isInvisible(current)) {
                hasVisibleCharacter = true;
            }
        }
        return hasVisibleCharacter;
    }

    private boolean isRefused(int character) {
        if (Character.isISOControl(character)) {
            return !(multiline && (character == '\n' || character == '\t'));
        }
        int type = Character.getType(character);
        return type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR
                // Half of an emoji (a lone UTF-16 surrogate, only possible through a JSON escape). It has no UTF-8
                // form, so PostgreSQL could not store the text exactly as the preview showed it.
                || type == Character.SURROGATE
                || isBidiControl(character);
    }

    // U+202A..U+202E (embedding and override) and U+2066..U+2069 (isolate). The left-to-right and
    // right-to-left marks U+200E, U+200F only mark a direction, cannot reorder text and stay allowed.
    private static boolean isBidiControl(int character) {
        return (character >= 0x202A && character <= 0x202E) || (character >= 0x2066 && character <= 0x2069);
    }

    // Spaces, line breaks and format characters (Unicode type Cf, for example U+200B or U+2060) show nothing.
    // Inside visible text a format character is kept: U+200D joins the people of the family emoji.
    private static boolean isInvisible(int character) {
        return Character.isWhitespace(character)
                || Character.isSpaceChar(character)
                || Character.getType(character) == Character.FORMAT;
    }

    private static boolean startsHtml(int next) {
        return (next >= 'a' && next <= 'z') || (next >= 'A' && next <= 'Z')
                || next == '/' || next == '!' || next == '?';
    }
}
