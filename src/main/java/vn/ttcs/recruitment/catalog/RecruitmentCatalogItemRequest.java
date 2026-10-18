package vn.ttcs.recruitment.catalog;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// Body of POST and PUT. The catalog type comes from the URL and the server sets sortOrder,
// so a body containing "type" or "sortOrder" is rejected like any other unknown field.
public record RecruitmentCatalogItemRequest(
        @NotBlank(message = "Mã giá trị danh mục không được để trống.")
        @Size(max = 50, message = "Mã giá trị danh mục tối đa 50 ký tự.") String code,
        @NotBlank(message = "Tên giá trị danh mục không được để trống.")
        @Size(max = 255, message = "Tên giá trị danh mục tối đa 255 ký tự.") String name,
        @NotNull(message = "Cần xác định giá trị danh mục đang được áp dụng hay không.") Boolean active) {

    // The characters nobody can see are removed around the code and the name. A value without a visible character
    // becomes null, so @NotBlank refuses it like an empty one.
    public RecruitmentCatalogItemRequest {
        code = visibleText(code);
        name = visibleText(name);
    }

    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unsupported recruitment catalog field");
    }

    // String.trim() and @NotBlank are not enough. Both keep the non-breaking spaces U+00A0, U+2007, U+202F, which text
    // pasted from Word or a web page often holds, U+0085, U+180E and zero-width characters such as U+200B or U+FEFF,
    // and trim() also keeps U+3000 at the ends. V10 only checks btrim, which removes ASCII spaces, so a code or name
    // made only of them was stored and showed nothing, and "OTHER" followed by one of them was a second code that looks
    // like OTHER. Every character that is not visible is removed at both ends; the characters inside are kept.
    private static String visibleText(String value) {
        if (value == null) {
            return null;
        }
        int start = 0;
        int end = value.length();
        while (start < end && !isVisible(value.codePointAt(start))) {
            start += Character.charCount(value.codePointAt(start));
        }
        while (end > start && !isVisible(value.codePointBefore(end))) {
            end -= Character.charCount(value.codePointBefore(end));
        }
        return start == end ? null : value.substring(start, end);
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
