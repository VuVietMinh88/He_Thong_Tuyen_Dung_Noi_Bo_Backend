package vn.ttcs.recruitment.interviewquestion;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

// One question of the interview question bank (Jira 220). It belongs to one criterion of a competency framework,
// so it is reached from a position through position -> framework -> criterion.
@Entity
@Table(name = "interview_questions")
public class InterviewQuestion {

    @Id
    private UUID id;

    // The CompetencyCriterion this question checks. V9 refuses to delete a criterion that still has questions.
    @Column(nullable = false)
    private UUID criterionId;

    // TEXT in PostgreSQL: a question may be long and span several lines.
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InterviewQuestionDifficulty difficulty;

    // What a good answer should contain; null when HR gives no hint.
    @Column(columnDefinition = "TEXT")
    private String answerHint;

    // false means the question is no longer used; the row is kept instead of deleted.
    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected InterviewQuestion() {
    }

    // A new question is active. The caller (the question API of later tasks) checks the criterion and the texts.
    public InterviewQuestion(UUID criterionId, String content, InterviewQuestionDifficulty difficulty,
                             String answerHint, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.criterionId = criterionId;
        this.content = content;
        this.difficulty = difficulty;
        this.answerHint = answerHint;
        this.active = true;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getCriterionId() { return criterionId; }
    public String getContent() { return content; }
    public InterviewQuestionDifficulty getDifficulty() { return difficulty; }
    public String getAnswerHint() { return answerHint; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
