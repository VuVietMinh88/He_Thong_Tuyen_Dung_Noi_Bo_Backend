package vn.ttcs.recruitment.talentpool;

import org.springframework.http.HttpStatus;
import vn.ttcs.recruitment.candidate.CandidateLabels;
import vn.ttcs.recruitment.common.ApiException;

import java.util.List;
import java.util.Map;

/**
 * Task 399 (story S5-06, "Kho tìm kiếm được theo chức danh quan tâm và kỹ năng"): the conditions of a talent pool search.
 * title finds entries with a title of interest containing it, skills those having every listed skill; both compare the
 * case- and accent-insensitive keys (CandidateLabels), so "lap trinh" finds "Lập trình viên Java".
 */
record TalentPoolSearch(String titleKey, List<String> skillKeys, int page, int size) {
    static final int MAX_SKILLS = 10;

    static TalentPoolSearch of(String title, List<String> skills, int page, int size) {
        if (page < 0 || page > 1_000_000 || size < 1 || size > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "Trang phải từ 0 đến 1000000; kích thước trang phải từ 1 đến 100.");
        }
        String titleKey = null;
        if (title != null && !title.isBlank()) {
            titleKey = CandidateLabels.labels(List.of(title), "title", 1, 100).getFirst().key();
            if (titleKey.length() < 2) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Vui lòng kiểm tra điều kiện tìm kiếm.",
                        Map.of("title", "Từ khóa chức danh tối thiểu 2 ký tự."));
            }
        }
        List<String> skillKeys = CandidateLabels.labels(skills, "skill", MAX_SKILLS, 60).stream()
                .map(CandidateLabels.Label::key).toList();
        return new TalentPoolSearch(titleKey, skillKeys, page, size);
    }

    long offset() { return (long) page * size; }

    // The title key as a LIKE pattern; the wildcards and the escape character ! are searched as themselves.
    String titlePattern() {
        return "%" + titleKey.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
}
