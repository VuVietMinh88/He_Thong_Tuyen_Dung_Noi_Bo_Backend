package vn.ttcs.recruitment.notification;

import jakarta.mail.MessagingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.common.MailFailureClassifier;
import vn.ttcs.recruitment.mail.MailBody;
import vn.ttcs.recruitment.mail.MailLayout;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Task 551: sends the email copy of a notification after NotificationWorker committed it. The copy keeps ids only, so
 * the worker rebuilds it from current data just before SMTP, in one read transaction: the recipient's current address
 * (an account disabled or locked meanwhile is not mailed, RECIPIENT_UNAVAILABLE), the recipient's EMAIL preference read
 * again (PREFERENCE_OFF), then the event planned again (NotificationPlanner, the same rules as the in-app notification:
 * a state that moved on or a recipient who lost access plans nothing, OBSOLETE). A copy queued longer ago than
 * app.notifications.email-max-age is cancelled as EXPIRED, never sent late. Subject and body are the
 * notification's texts in the shared MailLayout; the email asks the reader to sign in, it carries no link or secret. At
 * least once delivery: a crash after SMTP accepted the message sends it again after the lease expires. Delivery is an
 * operations switch (NOTIFICATION_EMAIL_DELIVERY_ENABLED, off by default like the other opt-in mail flows); while it is
 * off NotificationDispatch keeps no copies at all.
 */
@Component
public class NotificationEmailWorker {
    private static final Logger LOG = LoggerFactory.getLogger(NotificationEmailWorker.class);
    private final NotificationEmailOutbox outbox;
    private final NotificationPlanner planner;
    private final NotificationPreferences preferences;
    private final JavaMailSender sender;
    private final MailLayout layout;
    private final boolean deliveryEnabled;
    private final Duration maxAge;
    private final String from;
    private final TransactionTemplate reads;

    public NotificationEmailWorker(NotificationEmailOutbox outbox, NotificationPlanner planner,
                                   NotificationPreferences preferences, JavaMailSender sender, MailLayout layout,
                                   @Value("${app.notifications.email-delivery-enabled:false}") boolean deliveryEnabled,
                                   @Value("${app.notifications.email-max-age:24h}") Duration maxAge,
                                   @Value("${app.mail.from}") String from, PlatformTransactionManager manager) {
        if (maxAge.isNegative() || maxAge.isZero()) {
            throw new IllegalArgumentException("NOTIFICATION_EMAIL_MAX_AGE must be positive");
        }
        this.outbox = outbox;
        this.planner = planner;
        this.preferences = preferences;
        this.sender = sender;
        this.layout = layout;
        this.deliveryEnabled = deliveryEnabled;
        this.maxAge = maxAge;
        this.from = from;
        reads = new TransactionTemplate(manager);
        reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        reads.setReadOnly(true);
        reads.setTimeout(5);
    }

    @Scheduled(initialDelayString = "${app.notifications.email-poll-delay-ms:60000}",
            fixedDelayString = "${app.notifications.email-poll-delay-ms:60000}")
    public void poll() {
        try {
            for (int i = 0; i < 10 && processNext(); i++) { /* one bounded batch; no SMTP in a transaction */ }
        } catch (RuntimeException exception) {
            LOG.warn("Notification email processing failed; will retry on the next run");
        }
    }

    public boolean processNext() {
        if (!deliveryEnabled) return false;
        var claimed = outbox.claim(maxAge);
        if (claimed.isEmpty()) return false;
        var delivery = claimed.get();
        if (delivery.expired()) {
            outbox.cancel(delivery, "EXPIRED");
            return true;
        }
        Rebuilt email = reads.execute(status -> rebuild(delivery));
        if (email.cancelled() != null) {
            outbox.cancel(delivery, email.cancelled());
            return true;
        }
        if (!outbox.renew(delivery)) return true;
        try {
            send(email, delivery);
        } catch (MailPreparationException exception) {
            outbox.failed(delivery, true, "PAYLOAD_INVALID");
            return true;
        } catch (MailException exception) {
            outbox.failed(delivery, MailFailureClassifier.refusedRecipient(exception), "RECIPIENT_REFUSED");
            return true;
        }
        outbox.sent(delivery);
        return true;
    }

    // In this order: the account, the preference, then the notification itself from current data.
    private Rebuilt rebuild(NotificationEmailOutbox.Delivery delivery) {
        var recipient = outbox.recipient(delivery);
        if (recipient.isEmpty()) return Rebuilt.cancel("RECIPIENT_UNAVAILABLE");
        if (!preferences.enabled(delivery.recipientId(), delivery.type(), NotificationPreferences.Channel.EMAIL)) {
            return Rebuilt.cancel("PREFERENCE_OFF");
        }
        var event = outbox.event(delivery);
        if (event.isEmpty()) return Rebuilt.cancel("OBSOLETE");
        var planned = planner.plan(event.get()).stream()
                .filter(notification -> notification.recipient().equals(delivery.recipientId())
                        && notification.dedupeKey().equals(delivery.dedupeKey()))
                .findFirst();
        if (planned.isEmpty()) return Rebuilt.cancel("OBSOLETE");
        return new Rebuilt(recipient.get().email(), recipient.get().name(), planned.get().title(), planned.get().body(),
                null);
    }

    private void send(Rebuilt email, NotificationEmailOutbox.Delivery delivery) {
        var message = sender.createMimeMessage();
        try {
            var helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setValidateAddresses(true);
            helper.setFrom(from);
            helper.setTo(email.address());
            helper.setSubject("[Thông báo] " + email.title());
            layout.write(helper, MailBody.builder()
                    .paragraph(email.name() == null || email.name().isBlank() ? "Xin chào,"
                            : "Xin chào " + email.name() + ",")
                    .paragraph(email.body())
                    .paragraph("Vui lòng đăng nhập hệ thống tuyển dụng nội bộ để xem chi tiết.")
                    .build());
            message.setHeader("Message-ID", "<notification." + delivery.id() + "@" + domain() + ">");
        } catch (MessagingException | IllegalArgumentException | NullPointerException exception) {
            throw new MailPreparationException("Could not prepare notification email");
        }
        sender.send(message);
    }

    private String domain() {
        String address = from.contains("<") ? from.substring(from.indexOf('<') + 1, from.indexOf('>')) : from;
        return address.substring(address.lastIndexOf('@') + 1);
    }

    /** The email rebuilt from current data, or why it is not sent (cancelled). */
    private record Rebuilt(String address, String name, String title, String body, String cancelled) {
        static Rebuilt cancel(String reason) { return new Rebuilt(null, null, null, null, reason); }

        @Override public String toString() { return "NotificationEmail[redacted]"; }
    }
}
