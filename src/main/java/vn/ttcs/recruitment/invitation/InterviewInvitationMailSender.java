package vn.ttcs.recruitment.invitation;

import jakarta.mail.MessagingException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import vn.ttcs.recruitment.mail.MailLayout;
import vn.ttcs.recruitment.messaging.EmailTemplate;

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
        send(payload, messageId, null);
    }

    /**
     * Task 533: a candidate's invitation takes its subject and opening from the INTERVIEW_INVITATION template; the
     * details and links below stay fixed (InterviewMailContent). A null template keeps the fixed text.
     */
    public void send(InterviewMailPayload payload, String messageId, EmailTemplate.Rendered template) {
        var message = sender.createMimeMessage();
        try {
            var helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setValidateAddresses(true);
            helper.setFrom(settings.from());
            helper.setTo(payload.email());
            helper.setSubject(template == null ? InterviewMailContent.subject(payload) : template.subject());
            layout.write(helper, InterviewMailContent.body(payload, settings.zone(),
                    template == null ? null : template.paragraphs()));
            message.setHeader("Message-ID", messageId);
        } catch (MessagingException | IllegalArgumentException | NullPointerException exception) {
            // Rendering/address validation errors cannot improve after waiting for SMTP. Keep personal data out
            // of the exception: the worker marks this encrypted payload invalid and erases it immediately.
            throw new MailPreparationException("Could not prepare interview invitation");
        }
        sender.send(message);
    }
}
