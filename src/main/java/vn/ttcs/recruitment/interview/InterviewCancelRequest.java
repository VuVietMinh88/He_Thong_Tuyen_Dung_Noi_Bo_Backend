package vn.ttcs.recruitment.interview;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record InterviewCancelRequest(@NotNull @Min(0) Long expectedVersion,
                                     @NotBlank @Size(max = 2000) String reason) { }
