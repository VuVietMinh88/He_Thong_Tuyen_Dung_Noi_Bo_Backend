package vn.ttcs.recruitment.invitation;

import jakarta.mail.MessagingException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import vn.ttcs.recruitment.mail.MailLayout;

import java.nio.charset.StandardCharsets;

@Component
public class InterviewInvitationMailSender {
    private final JavaMailSender sender;
    private final MailLayout layout;
    private final InterviewInvitationSettings settings;

    public InterviewInvitationMailSender(JavaMailSender sender, MailLayout layout, InterviewInvitationSettings settings) {
        this.sender = sender; this.layout = layout; this.settings = settings;
    }

    public void send(InterviewMailPayload payload, String messageId) {
        var message = sender.createMimeMessage();
        try {
            var helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setValidateAddresses(true);
            helper.setFrom(settings.from());
            helper.setTo(payload.email());
            helper.setSubject(InterviewMailContent.subject(payload));
            layout.write(helper, InterviewMailContent.body(payload, settings.zone()));
            message.setHeader("Message-ID", messageId);
        } catch (MessagingException | IllegalArgumentException | NullPointerException exception) {
            // Rendering/address validation errors cannot improve after waiting for SMTP. Keep personal data out
            // of the exception: the worker marks this encrypted payload invalid and erases it immediately.
            throw new MailPreparationException("Could not prepare interview invitation");
        }
        sender.send(message);
    }
}
