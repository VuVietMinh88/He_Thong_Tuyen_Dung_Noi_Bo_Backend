package vn.ttcs.recruitment.interview;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Appointment logistics only: no CV, candidate address, salary, assessment or private notes. */
public record InterviewView(UUID id, UUID applicationId, String roundName, Instant startsAt, Instant endsAt,
                            InterviewMode mode, String room, String meetingUrl, String status, long version,
                            List<UUID> participantIds, UUID createdBy, Instant createdAt, Instant updatedAt,
                            Instant everSubmittedAt, String preparationInstructions) { }
