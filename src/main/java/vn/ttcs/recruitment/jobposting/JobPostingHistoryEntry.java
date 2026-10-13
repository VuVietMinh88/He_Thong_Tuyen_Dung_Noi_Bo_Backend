package vn.ttcs.recruitment.jobposting;

import java.time.Instant;
import java.util.UUID;

// Task 310: one step of the review and publication history of a posting (GET /job-postings/{id}/history), one row of
// job_posting_events. revision is the posting's revision right after the step. The actor is an id and a name, never an
// email or a role; comment is null when the step has none (always for PUBLISHED).
public record JobPostingHistoryEntry(UUID id, int revision, JobPostingEventType action, JobPostingStatus statusBefore,
                                     JobPostingStatus statusAfter, UUID actorId, String actorName,
                                     Instant occurredAt, String comment) { }
