package vn.ttcs.recruitment.interviewquestion;

import vn.ttcs.recruitment.competency.CompetencyCriterion;
import vn.ttcs.recruitment.competency.CompetencyFramework;

import java.time.Instant;
import java.util.UUID;

// One interview question, used by GET /{id}, POST and PUT (Jira 221). criterion and framework say where the question
// belongs, so a screen can show "Giao tiếp - Năng lực lập trình viên" without another request.
public record InterviewQuestionView(UUID id, Criterion criterion, Framework framework, String content,
                                    InterviewQuestionDifficulty difficulty, String answerHint, boolean active,
                                    Instant createdAt, Instant updatedAt) {

    public record Criterion(UUID id, String name) { }

    public record Framework(UUID id, String code, String name) { }

    static InterviewQuestionView from(InterviewQuestion question, CompetencyCriterion criterion,
                                      CompetencyFramework framework) {
        return new InterviewQuestionView(question.getId(), new Criterion(criterion.getId(), criterion.getName()),
                new Framework(framework.getId(), framework.getCode(), framework.getName()), question.getContent(),
                question.getDifficulty(), question.getAnswerHint(), question.isActive(), question.getCreatedAt(),
                question.getUpdatedAt());
    }
}
