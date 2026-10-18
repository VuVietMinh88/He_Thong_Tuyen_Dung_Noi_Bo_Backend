package vn.ttcs.recruitment.interviewquestionusage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** No candidate profile, answer scores or other evaluators' confidential comments are exposed here. */
public record InterviewQuestionUsageView(UUID questionId, UUID criterionId, String criterionName,
        String content, String answerHint, String difficulty, boolean asked, UUID askedBy, Instant askedAt) {
    public record Page<T>(List<T> items, int page, int size, long totalElements, long totalPages) { }
    public record History(UUID interviewId, String roundName, Instant interviewStartsAt,
                          InterviewQuestionUsageView question) { }
}
