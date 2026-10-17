package vn.ttcs.recruitment.jobposting;

import org.springframework.http.HttpStatus;
import vn.ttcs.recruitment.common.ApiException;

import java.text.Normalizer;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

// Task 315 (story S4-01): the filters and the keyword of GET /api/v1/public/job-postings after binding. Each one only
// narrows the postings the portal lists (JobPostingReadRepository.PUBLIC_WHERE), and they are combined with AND.
// A null id and an empty keyword mean no filter. An id that matches nothing (unknown, of another catalog, used only
// by postings that are not listed) gives an empty page, never an error, so the portal tells nothing about it.
record PublicJobPostingQuery(UUID departmentId, UUID workLocationId, UUID employmentTypeId, String keyword) {
    // A run of characters Unicode calls white space: spaces (non-breaking ones too), tabs and line breaks.
    private static final Pattern SPACES = Pattern.compile("\\p{IsWhite_Space}+");
    // Unicode category Cc (U+0000-U+001F, U+007F-U+009F). PostgreSQL cannot take U+0000 in a text parameter.
    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("\\p{Cc}");
    // Same limit as the other searches (interview questions, Jira 223).
    private static final int MAX_KEYWORD = 255;

    static PublicJobPostingQuery of(UUID departmentId, UUID workLocationId, UUID employmentTypeId, String q) {
        return new PublicJobPostingQuery(departmentId, workLocationId, employmentTypeId, keyword(q));
    }

    // The keyword as it is compared with the postings, like the search of InterviewQuestionService: Unicode NFC, so
    // a Vietnamese letter typed as a base letter plus combining marks still finds the text; each run of spaces, tabs
    // and line breaks becomes one space and the spaces around it are removed. "" means no search. Upper/lower case is
    // left to lower() in the SQL, so both sides use the same rule.
    private static String keyword(String q) {
        if (q == null) {
            return "";
        }
        String composed = Normalizer.normalize(q, Normalizer.Form.NFC);
        return SPACES.matcher(composed).replaceAll(" ").strip();
    }

    // Checked after page and size (JobPostingService.publicPage). The length counts the keyword after the steps
    // above, so spaces between words count once. The keyword is never repeated in the answer.
    void requireValid() {
        String error = null;
        if (keyword.length() > MAX_KEYWORD) {
            error = "Từ khóa tìm kiếm tối đa 255 ký tự.";
        } else if (CONTROL_CHARACTERS.matcher(keyword).find()) {
            error = "Từ khóa tìm kiếm không được chứa ký tự điều khiển.";
        }
        if (error != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "Từ khóa tìm kiếm tin tuyển dụng không hợp lệ.", Map.of("q", error));
        }
    }

    // The keyword as a LIKE pattern that finds it anywhere in the text. The LIKE wildcards are escaped with "!" (the
    // ESCAPE of the SQL), the escape character itself first, so %, _ and ! typed by a candidate are searched as
    // themselves.
    String likePattern() {
        return "%" + keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
}
