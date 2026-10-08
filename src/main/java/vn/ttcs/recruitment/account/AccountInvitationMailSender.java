package vn.ttcs.recruitment.account;

import jakarta.mail.MessagingException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

@Component
public class AccountInvitationMailSender {
    private final JavaMailSender sender;
    private final String from;
    private final URI activationPage;

    public AccountInvitationMailSender(JavaMailSender sender,
                                       @Value("${app.password-reset.mail-from}") String from,
                                       @Value("${app.account-activation.page-url}") URI activationPage) {
        this.sender = sender;
        this.from = from;
        this.activationPage = activationPage;
        boolean localHttp = "http".equals(activationPage.getScheme())
                && Set.of("localhost", "127.0.0.1", "[::1]").contains(
                activationPage.getHost() == null ? "" : activationPage.getHost());
        if (activationPage.getHost() == null || activationPage.getUserInfo() != null
                || activationPage.getQuery() != null || activationPage.getFragment() != null
                || !("https".equals(activationPage.getScheme()) || localHttp)) {
            throw new IllegalStateException("ACCOUNT_ACTIVATION_PAGE_URL must be a fixed HTTPS URL "
                    + "(HTTP allowed on localhost), without credentials, query or fragment.");
        }
    }

    public void send(String email, String temporaryPassword, String token, Duration ttl) {
        var message = sender.createMimeMessage();
        try {
            var helper = new MimeMessageHelper(message, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(email);
            helper.setSubject("Kích hoạt tài khoản — Hệ thống tuyển dụng nội bộ");
            helper.setText("Tài khoản nội bộ của bạn đã được tạo.\n\n"
                    + "Email đăng nhập: " + email + "\nMật khẩu tạm: " + temporaryPassword
                    + "\n\nKích hoạt trong " + ttl.toHours() + " giờ; liên kết chỉ dùng được một lần:\n"
                    + activationPage.toASCIIString() + "?token=" + token
                    + "\n\nSau khi kích hoạt, đăng nhập và đổi mật khẩu trong tài khoản của bạn.\n", false);
        } catch (MessagingException exception) {
            throw new MailPreparationException("Could not prepare account activation email.");
        }
        sender.send(message);
    }
}
