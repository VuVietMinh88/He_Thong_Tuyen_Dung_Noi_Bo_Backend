package vn.ttcs.recruitment.messaging;

import java.util.List;
import java.util.Objects;

/**
 * Task 532/533: the plain-text body of the application receipt. The template's paragraphs come first; a referral notice
 * (task 351) follows the first of them, the greeting; then the mandatory part, which no template can remove: the
 * position, the time of receipt, the tracking code with its link and the withdrawal link (task 345). The sender passes
 * the real code and links, the preview passes placeholders, so both show the same text.
 */
public final class ReceiptMailText {
    private ReceiptMailText() {
    }

    public static String compose(List<String> templateParagraphs, String referralParagraph, String postingTitle,
                                 String receivedAt, String trackingCode, String trackingUrl, String withdrawalUrl) {
        if (templateParagraphs == null || templateParagraphs.isEmpty()) {
            throw new IllegalArgumentException("A receipt needs the template's text");
        }
        StringBuilder text = new StringBuilder(1024);
        for (int index = 0; index < templateParagraphs.size(); index++) {
            text.append(templateParagraphs.get(index)).append("\n\n");
            // The referral paragraph ends with its own blank line (ApplicationConfirmationMailSender.referralParagraph).
            if (index == 0 && referralParagraph != null) text.append(referralParagraph);
        }
        return text.append("Vị trí ứng tuyển: ").append(Objects.requireNonNull(postingTitle)).append('\n')
                .append("Thời gian tiếp nhận: ").append(Objects.requireNonNull(receivedAt)).append(" (giờ Việt Nam)\n")
                .append("Mã tra cứu: ").append(Objects.requireNonNull(trackingCode)).append("\n\n")
                .append("Bạn có thể dùng liên kết sau để tra cứu hồ sơ:\n").append(Objects.requireNonNull(trackingUrl))
                .append("\n\n")
                .append("Vui lòng giữ mã và liên kết này để theo dõi hồ sơ.\n\n")
                .append("Nếu không muốn tiếp tục ứng tuyển, bạn có thể rút hồ sơ tại liên kết sau. Hệ thống sẽ hỏi email ")
                .append("đã dùng khi nộp hồ sơ và yêu cầu bạn xác nhận lại trước khi rút:\n")
                .append(Objects.requireNonNull(withdrawalUrl)).append("\n\n")
                .append("Email này xác nhận hồ sơ đã được tiếp nhận.\nKết quả tuyển dụng sẽ được thông báo sau.\n\n")
                .append("Hệ thống tuyển dụng nội bộ\n").toString();
    }
}
