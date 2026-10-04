package vn.ttcs.recruitment.auth.passwordreset;

import jakarta.mail.MessagingException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Set;

@Component
public class PasswordResetMailSender {

    private final JavaMailSender sender;
    private final String from;
    private final URI resetPage;

    public PasswordResetMailSender(JavaMailSender sender,
                                   @Value("${app.password-reset.mail-from}") String from,
                                   @Value("${app.password-reset.page-url}") URI resetPage) {
        this.sender = sender;
        this.from = from;
        this.resetPage = resetPage;
        boolean localHttp = "http".equals(resetPage.getScheme())
                && Set.of("localhost", "127.0.0.1", "[::1]").contains(
                        resetPage.getHost() == null ? "" : resetPage.getHost());
        if (resetPage.getHost() == null || resetPage.getUserInfo() != null
                || resetPage.getQuery() != null || resetPage.getFragment() != null
                || !("https".equals(resetPage.getScheme()) || localHttp)) {
            throw new IllegalStateException("RESET_PASSWORD_PAGE_URL must be a fixed HTTPS URL "
                    + "(HTTP allowed on localhost), without credentials, query or fragment.");
        }
    }

    public void send(String email, String token) {
        // Use a configured frontend URL; never trust the incoming Host header for recovery links.
        String link = resetPage.toASCIIString() + "?token=" + token;
        var message = sender.createMimeMessage();
        try {
            var helper = new MimeMessageHelper(message, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(email);
            helper.setSubject("Đặt lại mật khẩu — Hệ thống tuyển dụng nội bộ");
            helper.setText("Bạn đã yêu cầu đặt lại mật khẩu.\n\n"
                    + "Mở liên kết sau trong vòng 30 phút; liên kết chỉ dùng được một lần:\n"
                    + link + "\n\nNếu bạn không yêu cầu, hãy bỏ qua email này.\n", false);
        } catch (MessagingException exception) {
            throw new MailPreparationException("Could not prepare password reset email.");
        }
        sender.send(message);
    }
}
