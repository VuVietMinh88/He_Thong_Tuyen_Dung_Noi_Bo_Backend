package vn.ttcs.recruitment.jobposting;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

// Task 303: one posting of the list. The texts and the salary are only in the detail (JobPostingView).
public record JobPostingSummary(UUID id, UUID requisitionId, String title, String positionName,
                                String departmentName, JobPostingStatus status, int revision,
                                LocalDate applicationDeadline, String createdByName, Instant createdAt,
                                Instant updatedAt) {

    static JobPostingSummary from(JobPostingRow row) {
        return new JobPostingSummary(row.id(), row.requisitionId(), row.title(), row.positionName(),
                row.departmentName(), row.status(), row.revision(), row.applicationDeadline(), row.createdByName(),
                row.createdAt(), row.updatedAt());
    }
}
