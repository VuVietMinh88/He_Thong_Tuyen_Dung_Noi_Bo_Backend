package vn.ttcs.recruitment.application;

import jakarta.mail.MessagingException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import vn.ttcs.recruitment.messaging.EmailTemplate;
import vn.ttcs.recruitment.messaging.ReceiptMailText;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Component
public class ApplicationConfirmationMailSender {
    private final JavaMailSender sender;
    private final DateTimeFormatter time;
    private final ApplicationConfirmationSettings settings;

    // Task 345: the settings build the withdrawal link (task 344) at send time from the decrypted code, so the payload,
    // its encryption and V28 stay as they are, and a confirmation already queued gets the link too.
    public ApplicationConfirmationMailSender(JavaMailSender sender,@Value("${app.business-zone}") ZoneId zone,
            ApplicationConfirmationSettings settings) {
        this.sender=sender;time=DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(zone);this.settings=settings;
    }

    // Task 351 (story S4-07): referred is true when a staff member submitted the application through the referral API
    // (ApplicationReferralLookup at send time). The mail then says so after the greeting, with how to withdraw and whom
    // to ask to delete the data; it never names the referrer.
    // Task 533 (story S8-01): the subject and the opening come from the APPLICATION_RECEIPT template, rendered by the
    // worker at send time; ReceiptMailText adds the fixed part (position, time, tracking code and link, task 345's
    // withdrawal link), which no template can remove. V66's default template gives the text sent before templates.
    void send(ApplicationConfirmationPayload payload,String messageId,boolean referred,EmailTemplate.Rendered template) {
        var message=sender.createMimeMessage();
        try {
            var helper=new MimeMessageHelper(message,StandardCharsets.UTF_8.name());
            helper.setFrom(payload.from());helper.setTo(payload.email());
            helper.setSubject(template.subject());
            helper.setText(ReceiptMailText.compose(template.paragraphs(),referred?referralParagraph(payload.from()):null,
                    payload.postingTitle(),time.format(payload.receivedAt()),payload.trackingCode(),payload.trackingUrl(),
                    settings.withdrawalUrl(payload.trackingCode())),false);
            message.setHeader("Message-ID",messageId);
        } catch(MessagingException|IllegalArgumentException|NullPointerException exception) {
            throw new MailPreparationException("Could not prepare application confirmation");
        }
        sender.send(message);
    }

    // The sender's address (MAIL_FROM) is the contact for a deletion request; the referrer is not named (BA/PO 27(6)).
    // Public for the template preview (task 532), which shows a referred candidate's receipt with this same paragraph.
    public static String referralParagraph(String from) {
        return "Hồ sơ này do một nhân viên của công ty gửi thay bạn qua kênh giới thiệu nội bộ, kèm xác nhận rằng bạn "
                +"đã đồng ý. Nếu bạn không muốn tiếp tục, bạn có thể rút hồ sơ bằng liên kết rút hồ sơ bên dưới. Để yêu "
                +"cầu xóa dữ liệu của bạn, vui lòng trả lời email này hoặc liên hệ "+from+".\n\n";
    }
}
