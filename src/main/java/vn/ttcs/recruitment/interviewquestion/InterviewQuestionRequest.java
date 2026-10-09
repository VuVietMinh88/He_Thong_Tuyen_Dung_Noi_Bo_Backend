package vn.ttcs.recruitment.interviewquestion;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.text.Normalizer;
import java.util.UUID;

// Body of POST and PUT /api/v1/interview-questions (Jira 221). PUT replaces the whole question.
// criterionId is a criterion of any competency framework; InterviewQuestionService checks that it exists.
// active is optional. Left out (or null), POST creates a question in use and PUT keeps the current value.
// Jira 222: difficulty is read as text, so a wrong value becomes a field error that names the accepted values
// (an enum field would fail the whole body as INVALID_JSON), and the texts may not hold control characters.
public record InterviewQuestionRequest(
        @NotNull(message = "Cần chọn tiêu chí đánh giá cho câu hỏi.") UUID criterionId,
        @NotBlank(message = "Nội dung câu hỏi không được để trống.")
        @Size(max = InterviewQuestionRequest.MAX_CONTENT, message = "Nội dung câu hỏi tối đa 2000 ký tự.")
        @Pattern(regexp = InterviewQuestionRequest.NO_CONTROL_CHARACTERS,
                message = "Nội dung câu hỏi không được chứa ký tự điều khiển; chỉ dùng được xuống dòng và tab.")
        String content,
        // The names of InterviewQuestionDifficulty, written exactly (upper case, no spaces around them).
        @NotNull(message = "Cần chọn mức độ khó của câu hỏi.")
        @Pattern(regexp = "EASY|MEDIUM|HARD",
                message = "Mức độ khó phải là EASY (dễ), MEDIUM (trung bình) hoặc HARD (khó).")
        String difficulty,
        @Size(max = InterviewQuestionRequest.MAX_ANSWER_HINT, message = "Gợi ý câu trả lời tối đa 4000 ký tự.")
        @Pattern(regexp = InterviewQuestionRequest.NO_CONTROL_CHARACTERS,
                message = "Gợi ý câu trả lời không được chứa ký tự điều khiển; chỉ dùng được xuống dòng và tab.")
        String answerHint,
        Boolean active) {

    // V9 stores both texts as TEXT without a limit, so the API sets one. A question may span a few lines; what a
    // good answer contains may need more room.
    static final int MAX_CONTENT = 2000;
    static final int MAX_ANSWER_HINT = 4000;

    // Jira 222: every character may be used except the control characters (Unicode category Cc: U+0000-U+001F and
    // U+007F-U+009F), which are invisible and which PostgreSQL cannot always store (U+0000). Tab, line feed and
    // carriage return are control characters too, but they are allowed: they keep a long question readable.
    static final String NO_CONTROL_CHARACTERS = "[\\t\\n\\r\\P{Cc}]*";

    // The texts may span several lines: the line breaks inside stay, the spaces, tabs and line breaks around them
    // are removed (V9 rejects text that starts or ends with them). Content made only of spaces becomes "" and fails
    // @NotBlank. A blank answer hint is stored as null, because V9 stores "no hint" as NULL and rejects an empty text.
    // Jira 222: both texts are also put in Unicode form NFC. Some editors send a Vietnamese letter such as "ệ" as a
    // base letter plus combining marks (2 or 3 Java characters); NFC writes it as the single character other
    // editors send, so the same visible text is stored the same way and the length limits count letters as people
    // see them.
    public InterviewQuestionRequest {
        content = composed(stripSpaces(content));
        answerHint = composed(stripSpaces(answerHint));
        answerHint = answerHint == null || answerHint.isEmpty() ? null : answerHint;
    }

    // The difficulty as the enum the entity stores. Call it only after @Valid has checked the field.
    public InterviewQuestionDifficulty difficultyLevel() {
        return InterviewQuestionDifficulty.valueOf(difficulty);
    }

    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unsupported interview question field");
    }

    private static String composed(String value) {
        return value == null ? null : Normalizer.normalize(value, Normalizer.Form.NFC);
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
