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

    // A new question that is in use (active).
    public InterviewQuestion(UUID criterionId, String content, InterviewQuestionDifficulty difficulty,
                             String answerHint, Instant createdAt) {
        this(criterionId, content, difficulty, answerHint, true, createdAt);
    }

    // Jira 221: InterviewQuestionService checks the criterion and the texts before calling this.
    // active=false keeps a new question in the bank without using it yet.
    public InterviewQuestion(UUID criterionId, String content, InterviewQuestionDifficulty difficulty,
                             String answerHint, boolean active, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.criterionId = criterionId;
        this.content = content;
        this.difficulty = difficulty;
        this.answerHint = answerHint;
        this.active = active;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    // Jira 221: edits the question in place, so its id and createdAt never change. The question may move to another
    // criterion, even one of another framework; InterviewQuestionService checks that criterion first.
    public void update(UUID criterionId, String content, InterviewQuestionDifficulty difficulty, String answerHint,
                       boolean active, Instant updatedAt) {
        this.criterionId = criterionId;
        this.content = content;
        this.difficulty = difficulty;
        this.answerHint = answerHint;
        this.active = active;
        this.updatedAt = updatedAt;
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
