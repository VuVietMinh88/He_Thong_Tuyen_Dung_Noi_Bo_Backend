package vn.ttcs.recruitment.messaging;

import jakarta.mail.MessagingException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import vn.ttcs.recruitment.mail.MailLayout;

import java.nio.charset.StandardCharsets;

/**
 * Task 538: one rejection letter or thank-you, as a text/plain and text/html pair in the shared MailLayout with its
 * support footer. The template's paragraphs are raw text that MailLayout escapes.
 */
@Component
public class CandidateMailSender {
    private final JavaMailSender sender;
    private final MailLayout layout;
    private final CandidateMailSettings settings;

    public CandidateMailSender(JavaMailSender sender, MailLayout layout, CandidateMailSettings settings) {
        this.sender = sender;
        this.layout = layout;
        this.settings = settings;
    }

    public void send(String recipient, EmailTemplate.Rendered template, String messageId) {
        var message = sender.createMimeMessage();
        try {
            var helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setValidateAddresses(true);
            helper.setFrom(settings.from());
            helper.setTo(recipient);
            helper.setSubject(template.subject());
            layout.write(helper, CandidateNoticeMailContent.body(template.paragraphs()));
            message.setHeader("Message-ID", messageId);
        } catch (MessagingException | IllegalArgumentException | NullPointerException exception) {
            // An address or text that cannot be written will not improve by waiting: the worker fails it at once,
            // and the exception carries no personal data.
            throw new MailPreparationException("Could not prepare candidate email");
        }
        sender.send(message);
    }
}
