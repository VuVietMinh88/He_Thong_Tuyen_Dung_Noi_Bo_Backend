package vn.ttcs.recruitment.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.MailPreparationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vn.ttcs.recruitment.common.MailFailureClassifier;

import java.util.EnumMap;
import java.util.Map;

/**
 * Task 538 (story S8-02): delivers candidate_mail_outbox after the deciding transaction committed, so a rejection or an
 * evaluation never waits for SMTP. Claim, a check of current data, lease renewal, SMTP outside any transaction, then a
 * result fenced by the lease token; the database trigger logs the attempt (task 537) and updates the message.
 * Delivery is at least once: a crash after SMTP accepted the email and before the result was stored sends it again
 * when the lease expires, with the same Message-ID.
 */
@Component
public class CandidateMailWorker {
    private static final Logger LOG = LoggerFactory.getLogger(CandidateMailWorker.class);
    private final CandidateMailSettings settings;
    private final CandidateMailOutboxRepository outbox;
    private final CandidateMailSender mail;
    private final EmailTemplates templates;

    public CandidateMailWorker(CandidateMailSettings settings, CandidateMailOutboxRepository outbox,
                               CandidateMailSender mail, EmailTemplates templates) {
        this.settings = settings;
        this.outbox = outbox;
        this.mail = mail;
        this.templates = templates;
    }

    @Scheduled(initialDelayString = "${app.candidate-mail.poll-delay-ms:60000}",
            fixedDelayString = "${app.candidate-mail.poll-delay-ms:60000}")
    public void poll() {
        try {
            for (int i = 0; i < 10 && processNext(); i++) { /* One bounded batch; no SMTP in database transactions. */ }
        } catch (RuntimeException exception) {
            LOG.warn("Candidate mail processing failed; will retry on the next run");
        }
    }

    public boolean processNext() {
        if (!settings.deliveryEnabled()) return false;
        var claimed = outbox.claim();
        if (claimed.isEmpty()) return false;
        var delivery = claimed.get();
        var content = outbox.content(delivery);
        if (content.isEmpty()) {
            outbox.abandon(delivery, "OBSOLETE");
            return true;
        }
        String unavailable = CandidateMailPolicy.unavailableReason(content.get());
        if (unavailable != null) {
            outbox.abandon(delivery, unavailable);
            return true;
        }
        if (!outbox.renew(delivery)) return true;
        var current = content.get();
        Map<TemplateVariable, String> values = new EnumMap<>(TemplateVariable.class);
        values.put(TemplateVariable.CANDIDATE_NAME, current.candidateName());
        values.put(TemplateVariable.POSITION_TITLE, current.positionTitle());
        if (current.interviewStartsAt() != null) {
            values.put(TemplateVariable.INTERVIEW_TIME,
                    CandidateMailFormat.interviewTime(current.interviewStartsAt(), settings.zone()));
        }
        EmailTemplate.Rendered template = templates.render(current.type(), values);
        try {
            mail.send(current.recipient(), template, settings.messageId(delivery.id()));
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
}
