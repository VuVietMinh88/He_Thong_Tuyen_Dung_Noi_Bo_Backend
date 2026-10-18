package vn.ttcs.recruitment.evaluation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Every criterion is the form's own snapshot; no live framework changes alter this response. */
public record EvaluationView(UUID id, UUID interviewId, UUID applicationId, UUID evaluatorId,
                             Framework framework, String status, long version, List<Item> items,
                             EvaluationConclusion conclusion, BigDecimal totalScore, Instant firstSubmittedAt,
                             Instant submittedAt, Instant createdAt, Instant updatedAt) {
    public record Framework(UUID id, String code, String name) { }
    public record Item(UUID criterionId, String name, String description, BigDecimal weight, int sortOrder,
                       Integer score, String comment) { }
}
