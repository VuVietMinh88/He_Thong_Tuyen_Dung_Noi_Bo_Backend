package vn.ttcs.recruitment.candidatesearch;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import org.springframework.http.HttpStatus;
import vn.ttcs.recruitment.application.ApplicationStage;
import vn.ttcs.recruitment.candidate.CandidateText;
import vn.ttcs.recruitment.common.ApiException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Task 392 (story S5-05, "Lọc theo vị trí, giai đoạn, nguồn, nhãn, khoảng thời gian nộp, mức đánh giá; tìm theo tên,
 * email, số điện thoại"): the conditions of one candidate search. Task 393 adds coverLetterKeyword, searched in the
 * application's own cover letter and combined (AND) with keyword and every filter. Every condition applies to the same application row,
 * so a keyword found in one application never combines with the stage of another application of the same person.
 * <p>
 * An empty list means "no condition". Without an explicit stage list WITHDRAWN applications are left out, like the
 * board. Dates are business dates (app.business-zone), both ends included. Task 394: a repeated list value counts once
 * and is kept once, so a saved filter stores the lists exactly as they are checked.
 */
public record CandidateSearchCriteria(UUID requisitionId, UUID positionId, List<ApplicationStage> stages,
                                      List<UUID> sourceIds, List<UUID> tagIds, TagMatch tagMatch,
                                      LocalDate submittedFrom, LocalDate submittedTo, Integer minRating,
                                      Integer maxRating, String keyword, String coverLetterKeyword) {
    static final int MAX_LIST = 20;
    static final int MIN_KEYWORD = 2;
    static final int MAX_KEYWORD = 100;
    static final LocalDate FIRST_DATE = LocalDate.of(2000, 1, 1);
    static final LocalDate LAST_DATE = LocalDate.of(2100, 12, 31);
    private static final String INVALID = "Vui lòng kiểm tra điều kiện tìm kiếm.";

    public CandidateSearchCriteria {
        stages = copy(stages);
        sourceIds = copy(sourceIds);
        tagIds = copy(tagIds);
        tagMatch = tagMatch == null ? TagMatch.ANY : tagMatch;
        keyword = blankToNull(keyword);
        coverLetterKeyword = blankToNull(coverLetterKeyword);
    }

    // Saved filters (task 394) are read as JSON: a field this record does not have is refused, never ignored.
    @JsonAnySetter
    public void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("Unsupported candidate search field");
    }

    // Every problem at once, field by field, so a form can highlight each of them. No message repeats a typed value.
    public Map<String, String> problems() {
        var errors = new LinkedHashMap<String, String>();
        list(errors, "stages", stages, "giai đoạn");
        list(errors, "sourceIds", sourceIds, "nguồn");
        list(errors, "tagIds", tagIds, "nhãn");
        date(errors, "submittedFrom", submittedFrom);
        date(errors, "submittedTo", submittedTo);
        if (!errors.containsKey("submittedFrom") && !errors.containsKey("submittedTo") && submittedFrom != null
                && submittedTo != null && submittedFrom.isAfter(submittedTo)) {
            errors.put("submittedTo", "Ngày kết thúc phải sau hoặc bằng ngày bắt đầu.");
        }
        rating(errors, "minRating", minRating);
        rating(errors, "maxRating", maxRating);
        if (!errors.containsKey("minRating") && !errors.containsKey("maxRating") && minRating != null
                && maxRating != null && minRating > maxRating) {
            errors.put("maxRating", "Mức đánh giá tối đa phải lớn hơn hoặc bằng mức tối thiểu.");
        }
        text(errors, "keyword", keyword);
        text(errors, "coverLetterKeyword", coverLetterKeyword);
        return errors;
    }

    public CandidateSearchCriteria requireValid() {
        var errors = problems();
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", INVALID, errors);
        }
        return this;
    }

    // The stages the query keeps: the explicit list, or every stage except WITHDRAWN.
    List<String> effectiveStages() {
        if (!stages.isEmpty()) return distinct(stages).stream().map(Enum::name).toList();
        return Arrays.stream(ApplicationStage.values()).filter(stage -> stage != ApplicationStage.WITHDRAWN)
                .map(Enum::name).toList();
    }

    List<UUID> distinctSources() { return distinct(sourceIds); }

    List<UUID> distinctTags() { return distinct(tagIds); }

    private static void list(Map<String, String> errors, String field, List<?> values, String label) {
        if (values.stream().anyMatch(Objects::isNull)) {
            errors.put(field, "Giá trị " + label + " không hợp lệ.");
        } else if (distinct(values).size() > MAX_LIST) {
            errors.put(field, "Chọn tối đa " + MAX_LIST + " " + label + ".");
        }
    }

    private static void date(Map<String, String> errors, String field, LocalDate value) {
        if (value != null && (value.isBefore(FIRST_DATE) || value.isAfter(LAST_DATE))) {
            errors.put(field, "Ngày phải từ 01/01/2000 đến 31/12/2100.");
        }
    }

    private static void rating(Map<String, String> errors, String field, Integer value) {
        if (value != null && (value < 1 || value > 5)) errors.put(field, "Mức đánh giá từ 1 đến 5.");
    }

    // A keyword: 2 to 100 characters once trimmed, no control or invisible formatting character.
    static void text(Map<String, String> errors, String field, String value) {
        if (value == null) return;
        int length = value.codePointCount(0, value.length());
        if (length < MIN_KEYWORD || length > MAX_KEYWORD) {
            errors.put(field, "Từ khóa từ " + MIN_KEYWORD + " đến " + MAX_KEYWORD + " ký tự.");
        } else if (value.codePoints().anyMatch(point -> Character.isISOControl(point)
                || Character.getType(point) == Character.FORMAT)) {
            errors.put(field, "Từ khóa chứa ký tự không hợp lệ.");
        }
    }

    // Invisible characters at the ends (no-break and zero-width spaces included) are not part of a keyword.
    static String blankToNull(String value) {
        return CandidateText.trim(value);
    }

    // Distinct values in the given order; a null stays (once) so problems() can report it.
    private static <T> List<T> copy(List<T> values) {
        return values == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(new LinkedHashSet<>(values)));
    }

    private static <T> List<T> distinct(List<T> values) {
        return List.copyOf(new LinkedHashSet<>(values));
    }

    @Override
    public String toString() {
        return "CandidateSearchCriteria[requisition=" + requisitionId + ", position=" + positionId
                + ", keyword " + (keyword == null ? "none" : "redacted")
                + ", cover letter keyword " + (coverLetterKeyword == null ? "none" : "redacted") + "]";
    }
}
