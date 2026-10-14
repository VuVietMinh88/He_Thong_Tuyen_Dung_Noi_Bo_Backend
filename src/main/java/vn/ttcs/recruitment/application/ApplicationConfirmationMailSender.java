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

    public ApplicationConfirmationMailSender(JavaMailSender sender,@Value("${app.business-zone}") ZoneId zone) {
        this.sender=sender;time=DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(zone);
    }

    void send(ApplicationConfirmationPayload payload,String messageId) {
        var message=sender.createMimeMessage();
        try {
            var helper=new MimeMessageHelper(message,StandardCharsets.UTF_8.name());
            helper.setFrom(payload.from());helper.setTo(payload.email());
            helper.setSubject("Xác nhận nhận hồ sơ ứng tuyển — Hệ thống tuyển dụng nội bộ");
            helper.setText("Chào "+payload.fullName()+",\n\n"
                    +"Hệ thống đã nhận hồ sơ ứng tuyển của bạn.\n\n"
                    +"Vị trí ứng tuyển: "+payload.postingTitle()+"\n"
                    +"Thời gian tiếp nhận: "+time.format(payload.receivedAt())+" (giờ Việt Nam)\n"
                    +"Mã tra cứu: "+payload.trackingCode()+"\n\n"
                    +"Bạn có thể dùng liên kết sau để tra cứu hồ sơ:\n"+payload.trackingUrl()+"\n\n"
                    +"Vui lòng giữ mã và liên kết này để theo dõi hồ sơ.\n\n"
                    +"Email này xác nhận hồ sơ đã được tiếp nhận.\nKết quả tuyển dụng sẽ được thông báo sau.\n\n"
                    +"Hệ thống tuyển dụng nội bộ\n",false);
            message.setHeader("Message-ID",messageId);
        } catch(MessagingException exception) {throw new MailPreparationException("Could not prepare application confirmation");}
        sender.send(message);
    }
}
