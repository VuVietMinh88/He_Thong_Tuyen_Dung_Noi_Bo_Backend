package vn.ttcs.recruitment.onboarding;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OnboardingTemplateView(UUID id, UUID departmentId, String name, boolean active, long version,
                                     List<Item> items, Instant createdAt, Instant updatedAt) {
    public record Item(UUID id, String title, UUID assigneeId, int dueOffsetDays, int position) { }
    public record Page(List<OnboardingTemplateView> items, int page, int size, long totalElements, long totalPages) { }
}
