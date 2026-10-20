package vn.ttcs.recruitment.notification;

import java.util.Objects;
import java.util.UUID;

/**
 * Task 543/545: the screen a notification opens, as an entity reference built by the server: a requisition, a job
 * posting, an application, or an interview or offer with the application it belongs to (parentId). Never a URL, so a
 * notification can never send a user to another site.
 */
public record NotificationLink(Type type, UUID id, UUID parentId) {
    public enum Type { REQUISITION, JOB_POSTING, INTERVIEW, OFFER, APPLICATION }

    public NotificationLink {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
        boolean child = switch (type) {
            case INTERVIEW, OFFER -> true;
            case REQUISITION, JOB_POSTING, APPLICATION -> false;
        };
        if (child != (parentId != null)) {
            throw new IllegalArgumentException("Interview and offer links name their application, other links do not");
        }
    }

    public static NotificationLink requisition(UUID id) { return new NotificationLink(Type.REQUISITION, id, null); }
    public static NotificationLink jobPosting(UUID id) { return new NotificationLink(Type.JOB_POSTING, id, null); }
    public static NotificationLink application(UUID id) { return new NotificationLink(Type.APPLICATION, id, null); }
    public static NotificationLink interview(UUID id, UUID application) { return new NotificationLink(Type.INTERVIEW, id, application); }
    public static NotificationLink offer(UUID id, UUID application) { return new NotificationLink(Type.OFFER, id, application); }
}
