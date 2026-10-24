package vn.ttcs.recruitment.notification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Task 545: the inbox as the API returns it. */
public final class NotificationViews {
    private NotificationViews() {
    }

    /** The screen to open: an entity type, its id and, for an interview or an offer, its application. */
    public record Link(String type, UUID id, UUID applicationId) {
    }

    public record Item(UUID id, String type, String typeName, String title, String body, Link link, Instant createdAt,
                       Instant readAt, boolean read) {
        static Item of(NotificationRepository.Stored stored) {
            return new Item(stored.id(), stored.type().name(), stored.type().displayName(), stored.title(), stored.body(),
                    new Link(stored.link().type().name(), stored.link().id(), stored.link().parentId()),
                    stored.createdAt(), stored.readAt(), stored.readAt() != null);
        }
    }

    public record Page(List<Item> items, int page, int size, long totalItems, int totalPages, long unreadCount) {
    }

    public record UnreadCount(long unreadCount) {
    }

    public record ReadAll(int updated, long unreadCount) {
    }
}
