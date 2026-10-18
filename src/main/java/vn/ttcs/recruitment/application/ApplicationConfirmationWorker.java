package vn.ttcs.recruitment.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vn.ttcs.recruitment.common.MailFailureClassifier;

@Component
public class ApplicationConfirmationWorker {
    private static final Logger LOG=LoggerFactory.getLogger(ApplicationConfirmationWorker.class);
    private final ApplicationConfirmationSettings settings;
    private final ApplicationOutboxRepository outbox;
    private final ApplicationOutboxCrypto crypto;
    private final ApplicationConfirmationMailSender mail;
    private final ApplicationReferralLookup referrals;

    // Task 351: the referral lookup tells the mail sender, at send time, that a staff member submitted the application.
    public ApplicationConfirmationWorker(ApplicationConfirmationSettings settings,ApplicationOutboxRepository outbox,
            ApplicationOutboxCrypto crypto,ApplicationConfirmationMailSender mail,ApplicationReferralLookup referrals) {
        this.settings=settings;this.outbox=outbox;this.crypto=crypto;this.mail=mail;this.referrals=referrals;
    }

    @Scheduled(initialDelayString="${app.application-confirmation.poll-delay-ms:10000}",
            fixedDelayString="${app.application-confirmation.poll-delay-ms:10000}")
    public void poll() {
        try {for(int i=0;i<10 && processNext();i++) { /* bounded batch; no SMTP inside a database transaction */ }}
        catch(RuntimeException exception) {LOG.warn("Application confirmation processing failed; will retry on the next run");}
    }

    public boolean processNext() {
        if(!settings.ready())return false;
        var candidate=outbox.claim();if(candidate.isEmpty())return false;
        var delivery=candidate.get();
        ApplicationConfirmationPayload payload;
        try {payload=crypto.decrypt(delivery);}
        catch(RuntimeException exception) {outbox.failed(delivery,true,"PAYLOAD_INVALID");return true;}
        if(!outbox.renew(delivery))return true;
        // Task 351: a failed lookup leaves the row PROCESSING until its lease expires; it is then retried.
        boolean referred=referrals.isReferral(delivery.applicationId());
        try {mail.send(payload,delivery.messageId(),referred);}
        catch(MailException exception) {
            outbox.failed(delivery,MailFailureClassifier.refusedRecipient(exception),"RECIPIENT_REFUSED");return true;
        }
        // Fenced by lease_token: a late worker cannot overwrite a newer owner's result.
        outbox.sent(delivery);
        return true;
    }
}
