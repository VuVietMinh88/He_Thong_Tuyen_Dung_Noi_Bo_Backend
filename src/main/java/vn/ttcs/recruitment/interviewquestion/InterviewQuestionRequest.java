package vn.ttcs.recruitment.interviewquestion;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

// Body of POST and PUT /api/v1/interview-questions (Jira 221). PUT replaces the whole question.
// criterionId is a criterion of any competency framework; InterviewQuestionService checks that it exists.
// active is optional. Left out (or null), POST creates a question in use and PUT keeps the current value.
public record InterviewQuestionRequest(
        @NotNull(message = "Cần chọn tiêu chí đánh giá cho câu hỏi.") UUID criterionId,
        @NotBlank(message = "Nội dung câu hỏi không được để trống.")
        @Size(max = InterviewQuestionRequest.MAX_CONTENT, message = "Nội dung câu hỏi tối đa 2000 ký tự.")
        String content,
        @NotNull(message = "Cần chọn mức độ khó của câu hỏi.") InterviewQuestionDifficulty difficulty,
        @Size(max = InterviewQuestionRequest.MAX_ANSWER_HINT, message = "Gợi ý câu trả lời tối đa 4000 ký tự.")
        String answerHint,
        Boolean active) {

    // V9 stores both texts as TEXT without a limit, so the API sets one. A question may span a few lines; what a
    // good answer contains may need more room.
    static final int MAX_CONTENT = 2000;
    static final int MAX_ANSWER_HINT = 4000;

    // The texts may span several lines: the line breaks inside stay, the spaces, tabs and line breaks around them
    // are removed (V9 rejects text that starts or ends with them). Content made only of spaces becomes "" and fails
    // @NotBlank. A blank answer hint is stored as null, because V9 stores "no hint" as NULL and rejects an empty text.
    public InterviewQuestionRequest {
        content = stripSpaces(content);
        answerHint = stripSpaces(answerHint);
        answerHint = answerHint == null || answerHint.isEmpty() ? null : answerHint;
    }

    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unsupported interview question field");
    }

    // String.strip() is not enough: the V9 CHECK uses PostgreSQL's [[:space:]], which also counts the non-breaking
    // spaces (U+00A0, U+2007, U+202F) that text pasted from Word or a web page often ends with, plus U+0085 and
    // U+180E. Java does not call those whitespace, so they are removed here too.
    private static String stripSpaces(String value) {
        if (value == null) {
            return null;
        }
        int start = 0;
        int end = value.length();
        while (start < end && isSpace(value.charAt(start))) {
            start++;
        }
        while (end > start && isSpace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    // Every space and line break of Java (isWhitespace), every Unicode space including the non-breaking ones
    // (isSpaceChar), and two more characters PostgreSQL counts as spaces: U+0085 (next line) and U+180E.
    private static boolean isSpace(char character) {
        return Character.isWhitespace(character) || Character.isSpaceChar(character)
                || character == '\u0085' || character == '\u180E';
    }
}
