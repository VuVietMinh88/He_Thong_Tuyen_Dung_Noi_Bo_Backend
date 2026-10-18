package vn.ttcs.recruitment.interview;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Application order determines consecutive slots, independently of database lock order. */
public record InterviewBulkRequest(
        @NotNull UUID requestId,
        @NotNull @Size(min = 1, max = 50) List<@NotNull UUID> applicationIds,
        @NotBlank @Size(max = 120) String roundName,
        @NotNull Instant windowStart, @NotNull Instant windowEnd,
        @NotNull @Min(1) @Max(480) Integer durationMinutes,
        @NotNull InterviewMode mode, @Size(max = 200) String room, @Size(max = 2000) String meetingUrl,
        @NotNull @Size(min = 1, max = 20) List<@NotNull UUID> participantIds,
        Boolean overrideConflicts, @Size(max = 2000) String overrideReason,
        @Size(max = 2000) String preparationInstructions) { }
