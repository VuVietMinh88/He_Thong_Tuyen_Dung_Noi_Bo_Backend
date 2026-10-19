package vn.ttcs.recruitment.position;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

// Text fields are trimmed before validation, so padding never counts toward a length limit.
// Each salary is a whole VND amount from 0 to MAX_SALARY_VND. Comparing the two salaries needs both values,
// so PositionService checks salaryMin <= salaryMax and reports it on the salaryMax field.
public record PositionRequest(
        @NotBlank(message = "Mã chức danh không được để trống.")
        @Size(max = 50, message = "Mã chức danh tối đa 50 ký tự.") String code,
        @NotBlank(message = "Tên chức danh không được để trống.")
        @Size(max = 255, message = "Tên chức danh tối đa 255 ký tự.") String name,
        @NotBlank(message = "Cấp bậc không được để trống.")
        @Size(max = 50, message = "Cấp bậc tối đa 50 ký tự.") String level,
        @NotNull(message = "Lương tối thiểu không được để trống.")
        @PositiveOrZero(message = "Lương tối thiểu không được âm.")
        @Max(value = PositionRequest.MAX_SALARY_VND,
                message = "Lương tối thiểu không được vượt quá 1.000.000.000.000 đồng.")
        @JsonDeserialize(using = WholeVndDeserializer.class) Long salaryMin,
        @NotNull(message = "Lương tối đa không được để trống.")
        @PositiveOrZero(message = "Lương tối đa không được âm.")
        @Max(value = PositionRequest.MAX_SALARY_VND,
                message = "Lương tối đa không được vượt quá 1.000.000.000.000 đồng.")
        @JsonDeserialize(using = WholeVndDeserializer.class) Long salaryMax,
        @NotNull(message = "Cần xác định chức danh đang được áp dụng hay không.") Boolean active) {

    // 1.000 tỷ đồng: far above any real salary, so it only stops typing mistakes such as extra zeros and keeps
    // later offer calculations far from the long limit. V7 has no such CHECK; only the API enforces it.
    // Public because RequisitionRequest (task 244) uses the same ceiling for proposed salaries.
    public static final long MAX_SALARY_VND = 1_000_000_000_000L;

    public PositionRequest {
        // The characters nobody can see are removed around each text. A text without a visible character becomes
        // null, so @NotBlank refuses it like an empty one.
        code = visibleText(code);
        name = visibleText(name);
        level = visibleText(level);
    }

    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unsupported position field");
    }

    // String.trim() and @NotBlank are not enough. Both keep the non-breaking spaces U+00A0, U+2007, U+202F, which text
    // pasted from Word or a web page often holds, U+0085, U+180E and zero-width characters such as U+200B or U+FEFF,
    // and trim() also keeps U+3000 at the ends. V7 only checks btrim, which removes ASCII spaces, so a code, name or
    // level made only of them was stored and showed nothing, and "HR" followed by one of them was a second code that
    // looks like HR. Every character that is not visible is removed at both ends; the characters inside are kept.
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
