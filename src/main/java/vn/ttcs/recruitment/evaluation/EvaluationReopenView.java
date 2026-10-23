package vn.ttcs.recruitment.evaluation;

import java.time.Instant;
import java.util.UUID;

/** A recruiter may reopen a review, but that action must not disclose its confidential scores or comments. */
public record EvaluationReopenView(UUID id, UUID interviewId, String status, long version,
                                   Instant firstSubmittedAt, Instant updatedAt) { }
