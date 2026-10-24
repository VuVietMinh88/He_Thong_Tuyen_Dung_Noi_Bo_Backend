package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import vn.ttcs.recruitment.notification.NotificationLink;
import vn.ttcs.recruitment.notification.NotificationRepository;
import vn.ttcs.recruitment.notification.NotificationType;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Task 545: only the recipient reads, counts and marks its notifications; links are entity references. */
class NotificationInboxIntegrationTest extends ScreeningTestSupport {
    @Autowired NotificationRepository notifications;

    @Test
    void recipientSeesOwnNotificationsUnreadCountAndDeepLinksOnly() throws Exception {
        UUID approval = store(recruiter, NotificationType.APPROVAL_PENDING, NotificationLink.requisition(requisition), "k1", 0);
        UUID offer = store(recruiter, NotificationType.OFFER_RESPONDED,
                NotificationLink.offer(UUID.randomUUID(), first.applicationId()), "k2", 60);
        store(hr, NotificationType.RECRUITER_ASSIGNED, NotificationLink.requisition(requisition), "k3", 0);

        JsonNode inbox = ok(call("GET", "/api/v1/notifications", null, recruiterToken));
        assertThat(inbox.path("totalItems").asLong()).isEqualTo(2);
        assertThat(inbox.path("unreadCount").asLong()).isEqualTo(2);
        assertThat(inbox.path("items").path(0).path("id").asText()).isEqualTo(offer.toString());
        assertThat(inbox.path("items").path(0).path("link").path("type").asText()).isEqualTo("OFFER");
        assertThat(inbox.path("items").path(0).path("link").path("applicationId").asText()).isEqualTo(first.applicationId().toString());
        assertThat(inbox.path("items").path(1).path("typeName").asText()).isEqualTo("Yêu cầu chờ bạn duyệt");
        assertThat(ok(call("GET", "/api/v1/notifications/unread-count", null, recruiterToken)).path("unreadCount").asLong())
                .isEqualTo(2);

        JsonNode read = ok(call("PATCH", "/api/v1/notifications/" + approval + "/read", null, recruiterToken));
        assertThat(read.path("read").asBoolean()).isTrue();
        String firstReadAt = read.path("readAt").asText();
        clock.set(ApplicationTestSupport.NOW.plusSeconds(600));
        assertThat(ok(call("PATCH", "/api/v1/notifications/" + approval + "/read", null, recruiterToken)).path("readAt").asText())
                .isEqualTo(firstReadAt);
        assertThat(ok(call("GET", "/api/v1/notifications?unreadOnly=true", null, recruiterToken)).path("totalItems").asLong())
                .isEqualTo(1);
        // Someone else's notification cannot be read or marked: it does not exist for this caller.
        UUID hrs = jdbc.queryForObject("SELECT id FROM notifications WHERE recipient_id=?", UUID.class, hr);
        var foreign = call("PATCH", "/api/v1/notifications/" + hrs + "/read", null, recruiterToken);
        assertThat(foreign.statusCode()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT read_at IS NULL FROM notifications WHERE id=?", Boolean.class, hrs)).isTrue();
        assertThat(ok(call("POST", "/api/v1/notifications/read-all", null, recruiterToken)).path("updated").asInt()).isEqualTo(1);
        assertThat(ok(call("GET", "/api/v1/notifications/unread-count", null, recruiterToken)).path("unreadCount").asLong()).isZero();
        assertThat(ok(call("GET", "/api/v1/notifications/unread-count", null, hrToken)).path("unreadCount").asLong()).isEqualTo(1);
    }

    @Test
    void inboxValidatesPagesAndNeedsAnActiveSession() throws Exception {
        assertThat(call("GET", "/api/v1/notifications?size=101", null, recruiterToken).statusCode()).isEqualTo(400);
        assertThat(call("GET", "/api/v1/notifications?page=-1", null, recruiterToken).statusCode()).isEqualTo(400);
        assertThat(call("GET", "/api/v1/notifications", null, null).statusCode()).isEqualTo(401);
        jdbc.update("UPDATE auth_sessions SET revoked_at = ? WHERE user_id = ?",
                java.sql.Timestamp.from(ApplicationTestSupport.NOW), recruiter);
        assertThat(call("GET", "/api/v1/notifications", null, recruiterToken).statusCode()).isEqualTo(401);
    }

    private UUID store(UUID recipient, NotificationType type, NotificationLink link, String key, int seconds) {
        Instant at = ApplicationTestSupport.NOW.plusSeconds(seconds);
        new TransactionTemplate(transactions).executeWithoutResult(status -> notifications.insert(
                new NotificationRepository.New(recipient, type, "Tiêu đề " + key, "Nội dung " + key, link, key, at)));
        return jdbc.queryForObject("SELECT id FROM notifications WHERE recipient_id=? AND dedupe_key=?", UUID.class,
                recipient, key);
    }
}
