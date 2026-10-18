package vn.ttcs.recruitment.interview;

import java.time.Instant;
import java.util.UUID;

/** Resource-only conflict notice; deliberately omits application, candidate, round and meeting URL. */
public record InterviewConflict(UUID interviewId, Instant startsAt, Instant endsAt,
                                String resourceType, UUID participantId, String room) { }
