package vn.ttcs.recruitment.onboarding;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record OnboardingChecklistView(UUID id, UUID applicationId, UUID departmentId, UUID templateId,
                                      long templateVersion, String templateName, LocalDate startDate, String status,
                                      long version, int totalItems, int completedItems, int progressPercent,
                                      Instant completedAt, UUID completedBy, List<Item> items,
                                      Instant createdAt, Instant updatedAt) {
    public record Item(UUID id, String title, UUID assigneeId, LocalDate dueDate, String status, int position,
                       Instant completedAt, UUID completedBy) { }

    @Override public String toString() {
        return "OnboardingChecklistView[id=" + id + ", status=" + status + ", items redacted]";
    }
}
