package vn.ttcs.recruitment.candidatesearch;

import vn.ttcs.recruitment.candidate.CandidateText;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Task 392 (story S5-05): how a typed keyword becomes a bound LIKE pattern. Every value reaches SQL as a parameter;
 * the LIKE wildcards % and _ and the escape character ! itself are escaped, so they are searched as themselves.
 * <p>
 * Names are compared without Vietnamese accents and without case: "nguyen van" finds "Nguyễn Văn". The same table
 * folds both sides: FOLD_FROM/FOLD_TO are bound to translate() in SQL (FOLDED) and applied here to the keyword, so
 * the two sides can never use different rules. Both sides are taken in NFC first and every run of white space counts
 * as one space. The table also turns the Unicode space separators (no-break spaces included) into a space and deletes
 * zero-width formatting characters, so text typed with them still matches.
 */
final class CandidateSearchText {
    static final String FOLD_FROM;
    static final String FOLD_TO;
    // The named parameters of the fold table, bound by CandidateSearchRepository.
    static final String FROM_PARAMETER = "foldFrom";
    static final String TO_PARAMETER = "foldTo";
    private static final int MIN_PHONE_DIGITS = 3;
    private static final String[][] GROUPS = {
            {"àáạảãâầấậẩẫăằắặẳẵÀÁẠẢÃÂẦẤẬẨẪĂẰẮẶẲẴ", "a"},
            {"èéẹẻẽêềếệểễÈÉẸẺẼÊỀẾỆỂỄ", "e"},
            {"ìíịỉĩÌÍỊỈĨ", "i"},
            {"òóọỏõôồốộổỗơờớợởỡÒÓỌỎÕÔỒỐỘỔỖƠỜỚỢỞỠ", "o"},
            {"ùúụủũưừứựửữÙÚỤỦŨƯỪỨỰỬỮ", "u"},
            {"ỳýỵỷỹỲÝỴỶỸ", "y"},
            {"đĐ", "d"}};
    // Unicode space separators (U+00A0, U+1680, U+2000-U+200A, U+202F, U+205F, U+3000) and the line and paragraph
    // separators: each becomes an ordinary space.
    private static final int[] SPACE_SEPARATORS = {0x00A0, 0x1680, 0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005,
            0x2006, 0x2007, 0x2008, 0x2009, 0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000};
    // Formatting characters without visible width (soft hyphen, zero-width space, joiners, word joiner, BOM): placed
    // after the end of FOLD_TO, so translate() deletes them, and fold deletes them too.
    private static final int[] REMOVED = {0x00AD, 0x200B, 0x200C, 0x200D, 0x2060, 0xFEFF};

    static {
        StringBuilder from = new StringBuilder();
        StringBuilder to = new StringBuilder();
        for (String[] group : GROUPS) {
            from.append(group[0]);
            to.append(group[1].repeat(group[0].length()));
        }
        for (int point : SPACE_SEPARATORS) {
            from.appendCodePoint(point);
            to.append(' ');
        }
        for (int point : REMOVED) {
            from.appendCodePoint(point);
        }
        FOLD_FROM = from.toString();
        FOLD_TO = to.toString();
    }

    private CandidateSearchText() { }

    // The SQL form of fold for a text column: NFC (normalize() needs the UTF8 database the house already relies on),
    // the accents folded and the invisible characters replaced or deleted by the bound table, white space runs as one
    // space, then lower case.
    static String folded(String column) {
        return "lower(regexp_replace(translate(normalize(" + column + ", NFC), CAST(:" + FROM_PARAMETER + " AS text),"
                + " CAST(:" + TO_PARAMETER + " AS text)), '[[:space:]]+', ' ', 'g'))";
    }

    // The keyword as the SQL side sees a stored text: NFC, folded by the same table (formatting characters deleted),
    // one space per white space run, trimmed, lower case.
    static String fold(String keyword) {
        String text = Normalizer.normalize(keyword, Normalizer.Form.NFC);
        StringBuilder folded = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            int position = FOLD_FROM.indexOf(character);
            if (position >= FOLD_TO.length() || position < 0 && Character.getType(character) == Character.FORMAT) {
                continue;
            }
            folded.append(position < 0 ? character : FOLD_TO.charAt(position));
        }
        return CandidateText.collapseSpaces(folded.toString()).strip().toLowerCase(Locale.ROOT);
    }

    // A pattern that finds the literal anywhere.
    static String contains(String literal) {
        return "%" + escape(literal) + "%";
    }

    // The escape character first, so an escaped wildcard is never escaped twice.
    static String escape(String literal) {
        return literal.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    // The digits of a keyword that looks like a phone number (digits with spaces, dots, dashes, brackets or a leading
    // +), or null: a name such as "An" must not match every phone. Phones are stored as digits with an optional +.
    static String phoneDigits(String keyword) {
        String stripped = keyword.replaceAll("[\\s\\p{Z}().+-]", "");
        if (stripped.length() < MIN_PHONE_DIGITS || !stripped.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return null;
        }
        return stripped;
    }

    // The phone keyword in its national form, as CandidateSearchRepository.NATIONAL_PHONE reads a stored phone:
    // "+84 912..." becomes "0912..."; any other phone keyword is its digits. Null when the keyword is not a phone.
    static String nationalPhone(String keyword) {
        String digits = phoneDigits(keyword);
        if (digits == null) return null;
        String compact = keyword.replaceAll("[\\s\\p{Z}().-]", "");
        return compact.startsWith("+84") ? "0" + digits.substring(2) : digits;
    }
}
