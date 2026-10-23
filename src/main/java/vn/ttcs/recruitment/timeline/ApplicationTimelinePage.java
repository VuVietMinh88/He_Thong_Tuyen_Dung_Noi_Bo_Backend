package vn.ttcs.recruitment.timeline;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Task 403 (story S5-07): one page of one application's timeline, newest first. items and totalElements come from the
 * same statement, so they always agree; a page past the end has no items and the real total.
 */
public record ApplicationTimelinePage(UUID applicationId, List<ApplicationTimelineEvent> items, long totalElements,
                                      int page, int size) {
    public ApplicationTimelinePage {
        Objects.requireNonNull(applicationId, "applicationId");
        items = List.copyOf(items);
    }

    @Override
    public String toString() {
        return "ApplicationTimelinePage[" + applicationId + ", " + items.size() + " of " + totalElements + ", page "
                + page + ", size " + size + "]";
    }
}
