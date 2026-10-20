package vn.ttcs.recruitment.candidate;

import org.springframework.http.HttpStatus;
import vn.ttcs.recruitment.common.ApiException;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Short labels typed by staff about candidates and positions: skills (task 412 ff.) and job titles of interest (task
 * 398 ff.). The display form is NFC with invisible characters trimmed at the ends and every inner run of spaces as one
 * space (CandidateText: no-break and zero-width spaces count as invisible). The key, which decides whether two labels
 * are the same, also ignores case and Vietnamese accents and drops formatting characters, so "Kế toán", "ke toan" and
 * "KẾ TOÁN" are one label: a skill typed on a position matches the same skill typed on an application however it was
 * written.
 */
public final class CandidateLabels {
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");

    private CandidateLabels() { }

    public record Label(String display, String key) { }

    public static String key(String display) {
        StringBuilder visible = new StringBuilder(display.length());
        Normalizer.normalize(display, Normalizer.Form.NFC).codePoints()
                .filter(point -> Character.getType(point) != Character.FORMAT).forEach(visible::appendCodePoint);
        String lower = visible.toString().toLowerCase(Locale.ROOT);
        String bare = MARKS.matcher(Normalizer.normalize(lower, Normalizer.Form.NFD)).replaceAll("").replace('đ', 'd');
        String trimmed = CandidateText.trim(bare);
        return trimmed == null ? "" : CandidateText.collapseSpaces(trimmed);
    }

    // The display form of one typed value; empty when nothing visible is left.
    public static String display(String value) {
        String trimmed = CandidateText.trim(Normalizer.normalize(value, Normalizer.Form.NFC));
        return trimmed == null ? "" : CandidateText.collapseSpaces(trimmed);
    }

    // Distinct labels in the given order (the first spelling of a key wins); 400 VALIDATION_ERROR naming the field for
    // a missing, empty, too long or non-plain-text value, or too many values.
    public static List<Label> labels(List<String> values, String field, int maxCount, int maxLength) {
        if (values == null) return List.of();
        Map<String, Label> distinct = new LinkedHashMap<>();
        for (String value : values) {
            if (value == null) throw invalid(field, "Giá trị không được để trống.");
            String display = display(value);
            if (display.isEmpty() || key(display).isEmpty()) throw invalid(field, "Giá trị không được để trống.");
            if (display.codePointCount(0, display.length()) > maxLength) {
                throw invalid(field, "Mỗi giá trị tối đa " + maxLength + " ký tự.");
            }
            if (display.codePoints().anyMatch(point -> Character.isISOControl(point)
                    || Character.getType(point) == Character.FORMAT || point == '<' || point == '>')) {
                throw invalid(field, "Chỉ nhập văn bản thuần.");
            }
            distinct.putIfAbsent(key(display), new Label(display, key(display)));
        }
        if (distinct.size() > maxCount) throw invalid(field, "Tối đa " + maxCount + " giá trị.");
        return new ArrayList<>(distinct.values());
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Vui lòng kiểm tra dữ liệu đã nhập.",
                Map.of(field, message));
    }
}
