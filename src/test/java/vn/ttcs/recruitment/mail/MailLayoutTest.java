package vn.ttcs.recruitment.mail;

import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimePart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.mail.javamail.MimeMessageHelper;
import vn.ttcs.recruitment.common.BusinessCalendar;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MailLayoutTest {
    private static final String COMPANY = "Hệ thống tuyển dụng nội bộ";
    private static final String TEAM = "Bộ phận Nhân sự";
    private static final String SUPPORT = "support@internal-hire.com";
    private static final String TEST_LOGO = "classpath:mail/test-logo.png";
    private static final BusinessCalendar CALENDAR = new BusinessCalendar(
            Clock.fixed(Instant.parse("2026-10-16T03:00:00Z"), ZoneOffset.UTC), ZoneId.of("Asia/Ho_Chi_Minh"));
    private static final String LINK = "https://internal-hire.com/activate-account?token=abc_DEF-123";
    private static final MailBody INVITATION = MailBody.builder()
            .paragraph("Tài khoản nội bộ của bạn đã được tạo.")
            .field("Email đăng nhập", "new@example.test")
            .field("Mật khẩu tạm", "A7temporary")
            .paragraph("Kích hoạt trong 24 giờ; liên kết chỉ dùng được một lần:")
            .link("Kích hoạt tài khoản", LINK)
            .paragraph("Sau khi kích hoạt, đăng nhập và đổi mật khẩu trong tài khoản của bạn.")
            .build();

    @Test
    void plainTextKeepsTheFormerLinesAndEndsWithTheSupportFooter() {
        assertThat(layout(COMPANY, TEAM, SUPPORT, "", "").plainText(INVITATION)).isEqualTo("""
                Tài khoản nội bộ của bạn đã được tạo.

                Email đăng nhập: new@example.test
                Mật khẩu tạm: A7temporary

                Kích hoạt trong 24 giờ; liên kết chỉ dùng được một lần:
                https://internal-hire.com/activate-account?token=abc_DEF-123

                Sau khi kích hoạt, đăng nhập và đổi mật khẩu trong tài khoản của bạn.

                --\s
                Cần hỗ trợ? Liên hệ Bộ phận Nhân sự qua email support@internal-hire.com.
                Email này được gửi tự động từ Hệ thống tuyển dụng nội bộ.
                © 2026 Hệ thống tuyển dụng nội bộ
                """);
    }

    @Test
    void messageIsTextThenHtmlWithReplyToTheSupportMailboxAndNoImageWithoutALogo() throws Exception {
        MailLayout layout = layout(COMPANY, TEAM, SUPPORT, "", "");
        MimeMessage message = send(layout, INVITATION);
        assertThat(MailParts.structure(message))
                .isEqualTo("multipart/mixed[multipart/related[multipart/alternative[text/plain,text/html]]]");
        assertThat(message.getReplyTo()).extracting(Object::toString).containsExactly(SUPPORT);
        assertThat(MailParts.text(message).replace("\r\n", "\n")).isEqualTo(layout.plainText(INVITATION));
        String html = MailParts.html(message).replace("\r\n", "\n");
        assertThat(html).isEqualTo(layout.html(INVITATION))
                .contains("<span style=\"font-size:20px;font-weight:bold;color:#1d4ed8;\">" + COMPANY + "</span>",
                        "<strong>Mật khẩu tạm:</strong>", ">A7temporary</code>",
                        "<a href=\"" + LINK + "\"", ">Kích hoạt tài khoản</a>",
                        "Cần hỗ trợ? Liên hệ Bộ phận Nhân sự qua email <a href=\"mailto:" + SUPPORT + "\"",
                        "Email này được gửi tự động từ " + COMPANY + ".", "© 2026 " + COMPANY)
                .doesNotContain("<img", "cid:", "tel:", "số điện thoại");
        for (Part part : MailParts.leaves(message)) {
            assertThat(part.getContentType()).containsIgnoringCase("charset=UTF-8");
        }
    }

    @Test
    void phoneIsShownOnlyWhenConfigured() {
        MailLayout withPhone = layout(COMPANY, TEAM, SUPPORT, " (024) 3999.1234 ", "");
        assertThat(withPhone.plainText(INVITATION)).contains("Cần hỗ trợ? Liên hệ Bộ phận Nhân sự qua email "
                + SUPPORT + " hoặc số điện thoại (024) 3999.1234.\n");
        assertThat(withPhone.html(INVITATION)).contains(
                " hoặc số điện thoại <a href=\"tel:02439991234\" style=\"color:#2563eb;\">(024) 3999.1234</a>.");
        MailLayout withoutPhone = layout(COMPANY, TEAM, SUPPORT, "   ", "");
        assertThat(withoutPhone.plainText(INVITATION)).doesNotContain("số điện thoại");
        assertThat(withoutPhone.html(INVITATION)).doesNotContain("số điện thoại", "tel:");
    }

    @Test
    void everyValueIsEscapedInTheHtmlButKeptAsTypedInThePlainText() {
        MailLayout layout = layout("A<b>&\"", "Team <i>'x'</i>", SUPPORT, "", TEST_LOGO);
        MailBody body = MailBody.builder()
                .paragraph("Xin chào <script>alert(1)</script>\ndòng hai")
                .field("Nhãn <u>", "giá trị \"&\" <b>")
                .link("Mở <b>ngay</b>", "https://example.test/?a=1&b=\"x\"")
                .build();
        String html = layout.html(body);
        assertThat(html).contains("<title>A&lt;b&gt;&amp;&quot;</title>", "alt=\"A&lt;b&gt;&amp;&quot;\"",
                        "Email này được gửi tự động từ A&lt;b&gt;&amp;&quot;.",
                        "Liên hệ Team &lt;i&gt;&#39;x&#39;&lt;/i&gt; qua email",
                        "Xin chào &lt;script&gt;alert(1)&lt;/script&gt;<br>\ndòng hai",
                        "<strong>Nhãn &lt;u&gt;:</strong>", "giá trị &quot;&amp;&quot; &lt;b&gt;</code>",
                        "<a href=\"https://example.test/?a=1&amp;b=&quot;x&quot;\"", ">Mở &lt;b&gt;ngay&lt;/b&gt;</a>")
                .doesNotContain("A<b>", "<script>", "<i>", "<u>", "b=\"x\"");
        assertThat(layout.plainText(body)).contains("Xin chào <script>alert(1)</script>\ndòng hai",
                "Nhãn <u>: giá trị \"&\" <b>", "https://example.test/?a=1&b=\"x\"",
                "Liên hệ Team <i>'x'</i> qua email", "© 2026 A<b>&\"");
    }

    @Test
    void copyrightYearIsTheYearInTheBusinessTimeZone() throws Exception {
        // 17:30 UTC on 31 December is already 00:30 on 1 January in Vietnam.
        var calendar = new BusinessCalendar(Clock.fixed(Instant.parse("2026-12-31T17:30:00Z"), ZoneOffset.UTC),
                ZoneId.of("Asia/Ho_Chi_Minh"));
        var layout = new MailLayout(COMPANY, TEAM, SUPPORT, "", "", new DefaultResourceLoader(), calendar);
        assertThat(layout.plainText(INVITATION)).endsWith("\n© 2027 " + COMPANY + "\n");
        assertThat(layout.html(INVITATION)).contains("© 2027 " + COMPANY);
        MimeMessage message = send(layout, INVITATION);
        assertThat(MailParts.text(message)).contains("© 2027 " + COMPANY);
        assertThat(MailParts.html(message)).contains("© 2027 " + COMPANY);
    }

    @Test
    void logoIsOneInlinePngPartReferencedByTheHtmlHeader() throws Exception {
        MailLayout layout = layout(COMPANY, TEAM, SUPPORT, "", "  " + TEST_LOGO + "  ");
        MimeMessage message = send(layout, INVITATION);
        assertThat(MailParts.structure(message)).isEqualTo(
                "multipart/mixed[multipart/related[multipart/alternative[text/plain,text/html],image/png]]");
        MimePart image = MailParts.inline(message, "company-logo");
        assertThat(MailParts.baseType(image)).isEqualTo("image/png");
        assertThat(image.getDisposition()).isEqualToIgnoringCase(Part.INLINE);
        assertThat(image.getInputStream().readAllBytes())
                .isEqualTo(new DefaultResourceLoader().getResource(TEST_LOGO).getContentAsByteArray());
        assertThat(MailParts.html(message))
                .contains("<img src=\"cid:company-logo\" alt=\"" + COMPANY + "\" width=\"160\"")
                .doesNotContain("font-size:20px;font-weight:bold");
        assertThat(MailParts.text(message)).doesNotContain("cid:");
    }

    @Test
    void jpegLogoOfExactlyTheLimitIsAccepted(@TempDir Path directory) throws Exception {
        byte[] jpeg = new byte[MailLayout.MAX_LOGO_BYTES];
        jpeg[0] = (byte) 0xFF;
        jpeg[1] = (byte) 0xD8;
        jpeg[2] = (byte) 0xFF;
        Path file = Files.write(directory.resolve("logo.jpg"), jpeg);
        MimeMessage message = send(layout(COMPANY, TEAM, SUPPORT, "", file.toUri().toString()), INVITATION);
        assertThat(MailParts.structure(message)).endsWith(",image/jpeg]]");
    }

    @Test
    void logoThatIsMissingTooLargeOrNotAnImageStopsTheStartup(@TempDir Path directory) throws Exception {
        byte[] png = new DefaultResourceLoader().getResource(TEST_LOGO).getContentAsByteArray();
        byte[] tooLarge = Arrays.copyOf(png, MailLayout.MAX_LOGO_BYTES + 1);
        String tooLargeFile = Files.write(directory.resolve("large.png"), tooLarge).toUri().toString();
        String empty = Files.write(directory.resolve("empty.png"), new byte[0]).toUri().toString();
        String missingFile = directory.resolve("missing.png").toUri().toString();
        for (String location : List.of("classpath:mail/missing.png", missingFile, directory.toUri().toString(),
                tooLargeFile, empty, "classpath:application-default.properties")) {
            assertThatThrownBy(() -> layout(COMPANY, TEAM, SUPPORT, "", location)).as(location)
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("MAIL_LOGO");
        }
    }

    @Test
    void logoIsReadOnlyFromTheClassPathOrALocalFile() {
        // Any of these would otherwise be fetched over the network or resolved by the context on its own.
        ResourceLoader untouchable = new DefaultResourceLoader() {
            @Override
            public Resource getResource(String location) {
                throw new AssertionError("The logo location was opened: " + location);
            }
        };
        for (String location : List.of("https://example.test/logo.png", "http://127.0.0.1:9/logo.png",
                "jar:file:/opt/ttcs/app.jar!/mail/logo.png", "file://example.test/mail/logo.png",
                "file://localhost/opt/ttcs/mail-logo.png", "/mail/test-logo.png", "mail/test-logo.png",
                "classpath*:mail/test-logo.png", "CLASSPATH:mail/test-logo.png", "base64:iVBORw0KGgo=")) {
            assertThatThrownBy(() -> new MailLayout(COMPANY, TEAM, SUPPORT, "", location, untouchable, CALENDAR))
                    .as(location).isInstanceOf(IllegalStateException.class).hasMessageContaining("MAIL_LOGO");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "support", "support@internal-hire", "@internal-hire.com", "a b@internal-hire.com",
            "support@internal hire.com", "support@internal-hire.com?cc=x@y.com", "\"a\"@internal-hire.com",
            "a@b@internal-hire.com", "support@internal-hire.com, other@internal-hire.com"})
    void badSupportEmailStopsTheStartup(String email) {
        assertThatThrownBy(() -> layout(COMPANY, TEAM, email, "", ""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("MAIL_SUPPORT_EMAIL");
    }

    @Test
    void supportEmailMayHaveUpTo254Characters() {
        String domain = "@internal-hire.com";
        String longest = "a".repeat(254 - domain.length()) + domain;
        assertThat(layout(COMPANY, TEAM, longest, "", "").plainText(INVITATION)).contains(longest);
        assertThatThrownBy(() -> layout(COMPANY, TEAM, "a" + longest, "", ""))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345", "abc123456", "0901 234 567 ext 2", "0901-234-567;", "((((((", "+-() .-+()",
            "090123456789012345678", "0901\t234567"})
    void badSupportPhoneStopsTheStartup(String phone) {
        assertThatThrownBy(() -> layout(COMPANY, TEAM, SUPPORT, phone, ""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("MAIL_SUPPORT_PHONE");
    }

    @Test
    void namesMustBeShortVisibleTextWithoutControlOrFormatCharacters() {
        String nul = String.valueOf((char) 0);
        String zeroWidthSpace = String.valueOf((char) 0x200B);
        String rightToLeftOverride = String.valueOf((char) 0x202E);
        String replacement = String.valueOf((char) 0xFFFD);
        // What the names become when a UTF-8 .env holds them unescaped and Spring reads the file as ISO-8859-1:
        // "Hệ thống" gets C1 control characters, the other two only visible Latin-1 characters ("CÃ´ng ty").
        List<String> garbled = List.of("Hệ thống", "Công ty TNHH ABC", "Phòng Nhân sự").stream()
                .map(name -> new String(name.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1))
                .toList();
        List<String> invalid = new ArrayList<>(List.of("", "   ", "Bộ phận\nNhân sự", "Bộ phận\tNhân sự",
                "A" + nul, zeroWidthSpace, "Nhân" + zeroWidthSpace + "sự", rightToLeftOverride + "abc",
                "Nh" + replacement + "n sự", "x".repeat(121)));
        invalid.addAll(garbled);
        for (String name : invalid) {
            assertThatThrownBy(() -> layout(name, TEAM, SUPPORT, "", "")).as(name)
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("MAIL_COMPANY_NAME");
            assertThatThrownBy(() -> layout(COMPANY, name, SUPPORT, "", "")).as(name)
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("MAIL_SUPPORT_TEAM");
        }
        String longest = "ệ".repeat(120);
        assertThat(layout(longest, " " + TEAM + " ", SUPPORT, "", "").plainText(INVITATION))
                .contains("Liên hệ " + TEAM + " qua email", "© 2026 " + longest + "\n");
        // Correctly typed names whose letters all fit in ISO-8859-1 are not mistaken for garbled ones.
        for (String name : List.of("Công ty TNHH ABC", "Phòng Nhân", "Café Ô Mai", "ABC®")) {
            assertThat(layout(name, name, SUPPORT, "", "").plainText(INVITATION)).as(name)
                    .contains("Liên hệ " + name + " qua email", "© 2026 " + name + "\n");
        }
    }

    @Test
    void bodyNeedsABlockAndLinksMustBeWebAddresses() {
        assertThatThrownBy(() -> MailBody.builder().build()).isInstanceOf(IllegalArgumentException.class);
        for (String url : List.of("javascript:alert(1)", "mailto:a@b.c", "ftp://example.test/x", "/relative")) {
            assertThatThrownBy(() -> MailBody.builder().link("Mở", url)).as(url)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static MailLayout layout(String company, String team, String email, String phone, String logo) {
        return new MailLayout(company, team, email, phone, logo, new DefaultResourceLoader(), CALENDAR);
    }

    // Writes the message as it goes over SMTP and parses it back, as a mail client would receive it.
    private static MimeMessage send(MailLayout layout, MailBody body) throws Exception {
        Session session = Session.getInstance(new Properties());
        MimeMessage message = new MimeMessage(session);
        var helper = new MimeMessageHelper(message, MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED, "UTF-8");
        helper.setFrom("no-reply@example.test");
        helper.setTo("new@example.test");
        helper.setSubject("Kích hoạt tài khoản");
        layout.write(helper, body);
        message.saveChanges();
        var wire = new ByteArrayOutputStream();
        message.writeTo(wire);
        return new MimeMessage(session, new ByteArrayInputStream(wire.toByteArray()));
    }
}
