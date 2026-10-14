package vn.ttcs.recruitment.common;

import jakarta.mail.SendFailedException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;

public final class MailFailureClassifier {
    private MailFailureClassifier() { }

    public static boolean refusedRecipient(MailException failure) {
        if (failure instanceof MailSendException sendFailure) {
            for (Exception cause : sendFailure.getFailedMessages().values()) {
                if (cause instanceof SendFailedException refused && refused.getInvalidAddresses() != null
                        && refused.getInvalidAddresses().length > 0) return true;
            }
        }
        return false;
    }
}
