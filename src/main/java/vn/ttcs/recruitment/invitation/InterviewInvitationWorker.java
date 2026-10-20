package vn.ttcs.recruitment.invitation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.MailPreparationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vn.ttcs.recruitment.common.MailFailureClassifier;
import vn.ttcs.recruitment.messaging.CandidateMailFormat;
import vn.ttcs.recruitment.messaging.EmailTemplate;
import vn.ttcs.recruitment.messaging.EmailTemplateType;
import vn.ttcs.recruitment.messaging.EmailTemplates;
import vn.ttcs.recruitment.messaging.TemplateVariable;

import java.util.Map;

@Component
public class InterviewInvitationWorker {
    private static final Logger LOG = LoggerFactory.getLogger(InterviewInvitationWorker.class);
    private final InterviewInvitationSettings settings;
    private final InterviewInvitationOutboxRepository outbox;
    private final InterviewInvitationCrypto crypto;
    private final InterviewInvitationMailSender mail;
    private final EmailTemplates templates;

    // Task 533: a candidate's first invitation uses the INTERVIEW_INVITATION template current at send time.
    public InterviewInvitationWorker(InterviewInvitationSettings settings, InterviewInvitationOutboxRepository outbox,
            InterviewInvitationCrypto crypto, InterviewInvitationMailSender mail, EmailTemplates templates) {
        this.settings = settings; this.outbox = outbox; this.crypto = crypto; this.mail = mail; this.templates = templates;
    }

    @Scheduled(initialDelayString = "${app.interview-invitation.poll-delay-ms:60000}",
            fixedDelayString = "${app.interview-invitation.poll-delay-ms:60000}")
    public void poll() {
        try {
            for (int i = 0; i < 10 && processNext(); i++) { /* One bounded batch; no SMTP in database transactions. */ }
        } catch (RuntimeException exception) {
            LOG.warn("Interview invitation processing failed; will retry on the next run");
        }
    }

    public boolean processNext() {
        if (!settings.enabled() || !settings.deliveryEnabled() || !settings.ready()) return false;
        var claimed = outbox.claim();
        if (claimed.isEmpty()) return false;
        var delivery = claimed.get();
        InterviewMailPayload payload;
        try {
            payload = crypto.decrypt(delivery.id(), delivery.interviewId(), delivery.keyId(),
                    delivery.nonce(), delivery.ciphertext());
            if (payload == null) throw new IllegalArgumentException("Invalid encrypted invitation");
        } catch (RuntimeException exception) {
            outbox.failed(delivery, true, "PAYLOAD_INVALID");
            return true;
        }
        String unavailable = outbox.unavailableReason(delivery, payload);
        if (unavailable != null) {
            outbox.abandon(delivery, unavailable);
            return true;
        }
        if (!outbox.renew(delivery)) return true;
        EmailTemplate.Rendered template = null;
        if (delivery.recipientUserId() == null && "INVITATION".equals(delivery.kind())) {
            template = templates.render(EmailTemplateType.INTERVIEW_INVITATION, Map.of(
                    TemplateVariable.CANDIDATE_NAME, payload.recipientName(),
                    TemplateVariable.POSITION_TITLE, payload.positionTitle(),
                    TemplateVariable.INTERVIEW_TIME, CandidateMailFormat.interviewTime(payload.startsAt(), settings.zone())));
        }
        try { mail.send(payload, settings.messageId(delivery.id()), template); }
        catch (MailPreparationException exception) {
            outbox.failed(delivery, true, "PAYLOAD_INVALID");
            return true;
        }
        catch (MailException exception) {
            outbox.failed(delivery, MailFailureClassifier.refusedRecipient(exception), "RECIPIENT_REFUSED");
            return true;
        }
        outbox.sent(delivery);
        return true;
    }
}
