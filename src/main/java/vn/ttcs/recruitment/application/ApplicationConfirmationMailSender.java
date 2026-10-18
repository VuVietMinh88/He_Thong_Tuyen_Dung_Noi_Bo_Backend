package vn.ttcs.recruitment.application;

import jakarta.mail.MessagingException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

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

    void send(ApplicationConfirmationPayload payload,String messageId) {send(payload,messageId,false);}

    // Task 351 (story S4-07): referred is true when a staff member submitted the application through the referral API
    // (ApplicationReferralLookup at send time). The mail then says so after the greeting, with how to withdraw and whom
    // to ask to delete the data; it never names the referrer.
    void send(ApplicationConfirmationPayload payload,String messageId,boolean referred) {
        var message=sender.createMimeMessage();
        try {
            var helper=new MimeMessageHelper(message,StandardCharsets.UTF_8.name());
            helper.setFrom(payload.from());helper.setTo(payload.email());
            helper.setSubject("Xác nhận nhận hồ sơ ứng tuyển — Hệ thống tuyển dụng nội bộ");
            helper.setText("Chào "+payload.fullName()+",\n\n"
                    +(referred?referralParagraph(payload.from()):"")
                    +"Hệ thống đã nhận hồ sơ ứng tuyển của bạn.\n\n"
                    +"Vị trí ứng tuyển: "+payload.postingTitle()+"\n"
                    +"Thời gian tiếp nhận: "+time.format(payload.receivedAt())+" (giờ Việt Nam)\n"
                    +"Mã tra cứu: "+payload.trackingCode()+"\n\n"
                    +"Bạn có thể dùng liên kết sau để tra cứu hồ sơ:\n"+payload.trackingUrl()+"\n\n"
                    +"Vui lòng giữ mã và liên kết này để theo dõi hồ sơ.\n\n"
                    // Task 345 (story S4-06): the withdrawal link ships with the withdrawal API, never before it.
                    +"Nếu không muốn tiếp tục ứng tuyển, bạn có thể rút hồ sơ tại liên kết sau. Hệ thống sẽ hỏi email "
                    +"đã dùng khi nộp hồ sơ và yêu cầu bạn xác nhận lại trước khi rút:\n"
                    +settings.withdrawalUrl(payload.trackingCode())+"\n\n"
                    +"Email này xác nhận hồ sơ đã được tiếp nhận.\nKết quả tuyển dụng sẽ được thông báo sau.\n\n"
                    +"Hệ thống tuyển dụng nội bộ\n",false);
            message.setHeader("Message-ID",messageId);
        } catch(MessagingException exception) {throw new MailPreparationException("Could not prepare application confirmation");}
        sender.send(message);
    }

    // The sender's address (MAIL_FROM) is the contact for a deletion request; the referrer is not named (BA/PO 27(6)).
    static String referralParagraph(String from) {
        return "Hồ sơ này do một nhân viên của công ty gửi thay bạn qua kênh giới thiệu nội bộ, kèm xác nhận rằng bạn "
                +"đã đồng ý. Nếu bạn không muốn tiếp tục, bạn có thể rút hồ sơ bằng liên kết rút hồ sơ bên dưới. Để yêu "
                +"cầu xóa dữ liệu của bạn, vui lòng trả lời email này hoặc liên hệ "+from+".\n\n";
    }
}
