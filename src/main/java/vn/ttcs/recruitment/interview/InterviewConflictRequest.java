package vn.ttcs.recruitment.interview;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A preview only; create/update always perform their own serialized conflict check. */
public record InterviewConflictRequest(
        @NotNull UUID applicationId, UUID interviewId,
        @NotBlank @Size(max = 120) String roundName,
        @NotNull Instant startsAt, @NotNull @Min(1) @Max(480) Integer durationMinutes,
        @NotNull InterviewMode mode, @Size(max = 200) String room, @Size(max = 2000) String meetingUrl,
        @NotNull @Size(min = 1, max = 20) List<@NotNull UUID> participantIds) {
    InterviewScheduleRequest schedule() {
        return new InterviewScheduleRequest(roundName, startsAt, durationMinutes, mode, room, meetingUrl,
                participantIds, null, false, null);
    }
}
