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
import java.util.List;

/**
 * Task 346 (story S4-06): the two emails after a withdrawal, plain UTF-8 text from app.mail.from with constant
 * subjects, so no header carries data. A recruiter mail carries only staff-controlled text (the recipient's own name,
 * the posting-title snapshot), times and profile links: never the candidate's name, email, phone, cover letter,
 * reason, code or CV file name, so an anonymous applicant cannot make the system mail a recruiter their own words (the
 * name field accepts any visible line, URLs included). The candidate mail carries no name, code, reason or link.
 */
@Component
class ApplicationNoticeMailSender {
    static final String RECRUITER_SUBJECT = "Có ứng viên rút hồ sơ — Hệ thống tuyển dụng nội bộ";
    static final String CANDIDATE_SUBJECT = "Xác nhận rút hồ sơ ứng tuyển — Hệ thống tuyển dụng nội bộ";

    private final JavaMailSender sender;
    private final ApplicationNoticeSettings settings;
    private final DateTimeFormatter time;

    ApplicationNoticeMailSender(JavaMailSender sender, ApplicationNoticeSettings settings,
            @Value("${app.business-zone}") ZoneId zone) {
        this.sender = sender; this.settings = settings;
        time = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(zone);
    }

    // One mail for a batch of one recipient and requisition, one line per application still withdrawn.
    void sendRecruiter(ApplicationNoticeRepository.Recipient recipient,
                       List<ApplicationNoticeRepository.WithdrawnItem> items, String messageId) {
        var body = new StringBuilder("Chào ").append(recipient.fullName()).append(",\n\n")
                .append("Các hồ sơ ứng tuyển sau, thuộc yêu cầu tuyển dụng bạn phụ trách, vừa được ứng viên rút:\n");
        for (var item : items) {
            body.append("- Vị trí \"").append(item.postingTitle()).append("\", rút lúc ")
                    .append(time.format(item.withdrawnAt())).append(" (giờ Việt Nam).");
            if (item.candidateId() != null) {
                body.append(" Xem hồ sơ: ").append(settings.profileUrl(item.candidateId()));
            }
            body.append('\n');
        }
        body.append('\n')
                .append("Các hồ sơ này đã chuyển sang giai đoạn \"").append(ApplicationStage.WITHDRAWN.displayName())
                .append("\" và không còn được tính trong danh sách đang xử lý.\n")
                .append("Tên và thông tin liên hệ của ứng viên chỉ hiển thị sau khi đăng nhập.\n\n")
                .append("Email này được gửi tự động tới recruiter chính của yêu cầu tuyển dụng.\n")
                .append("Hệ thống tuyển dụng nội bộ\n");
        send(recipient.email(), RECRUITER_SUBJECT, body.toString(), messageId);
    }

    void sendCandidate(ApplicationNoticeRepository.CandidateNotice notice, String messageId) {
        String body = "Chào bạn,\n\n"
                + "Hồ sơ ứng tuyển vị trí \"" + notice.postingTitle() + "\" nộp lúc " + time.format(notice.receivedAt())
                + " đã được rút lúc " + time.format(notice.withdrawnAt()) + " (giờ Việt Nam)\n"
                + "theo yêu cầu gửi từ trang tra cứu hồ sơ.\n\n"
                + "Nếu không phải bạn thực hiện việc này, vui lòng trả lời email này hoặc liên hệ "
                + settings.supportAddress() + " để được hỗ trợ.\n\n"
                + "Hệ thống tuyển dụng nội bộ\n";
        send(notice.email(), CANDIDATE_SUBJECT, body, messageId);
    }

    private void send(String to, String subject, String body, String messageId) {
        var message = sender.createMimeMessage();
        try {
            var helper = new MimeMessageHelper(message, StandardCharsets.UTF_8.name());
            helper.setFrom(settings.from());
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(body, false);
            message.setHeader("Message-ID", messageId);
        } catch (MessagingException exception) {
            throw new MailPreparationException("Could not prepare application notice");
        }
        sender.send(message);
    }
}
