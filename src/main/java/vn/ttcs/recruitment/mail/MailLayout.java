package vn.ttcs.recruitment.mail;

import jakarta.mail.MessagingException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.util.ResourceUtils;
import org.springframework.web.util.HtmlUtils;
import vn.ttcs.recruitment.common.BusinessCalendar;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * The shared layout of the account emails (password reset, account activation, staff import invitations). It writes
 * one MailBody as a multipart/alternative pair, text/plain first and text/html second, and ends both with the same
 * support footer: who to contact (team, email, optional phone), that the email is sent automatically, and a copyright
 * line with the current year in app.business-zone. Reply-To is the support email.
 *
 * <p>The HTML part is a 560px card with inline CSS, as mail clients ignore style sheets. Its header shows the company
 * name as a text wordmark, or the configured logo (app.mail.logo) as an inline image that the HTML references as
 * cid:company-logo; no remote image is loaded, so the email looks the same offline and in clients that block remote
 * content. Every value inserted into the HTML, attribute values included, is HTML-escaped.
 *
 * <p>The configuration is checked once, at startup: a value that would make every email wrong stops the
 * application instead.
 */
@Component
public class MailLayout {
    static final String LOGO_CONTENT_ID = "company-logo";
    static final int MAX_LOGO_BYTES = 200 * 1024;
    private static final int MAX_NAME_LENGTH = 120;
    private static final int MAX_EMAIL_LENGTH = 254;
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+");
    private static final Pattern PHONE = Pattern.compile("[0-9+() .-]{6,20}");
    private static final int MIN_PHONE_DIGITS = 6;
    private static final int REPLACEMENT_CHARACTER = 0xFFFD;
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final String ENCODING = "UTF-8";
    private static final String LOGO_RULE = "MAIL_LOGO (app.mail.logo) must be empty or a classpath: or local file:"
            + " location of a readable PNG or JPEG image of at most 200 KB, for example classpath:mail/logo.png or"
            + " file:/opt/ttcs/mail-logo.png.";

    private final String companyName;
    private final String supportTeam;
    private final String supportEmail;
    private final String supportPhone;
    private final Logo logo;
    private final BusinessCalendar calendar;

    public MailLayout(@Value("${app.mail.company-name}") String companyName,
                      @Value("${app.mail.support-team}") String supportTeam,
                      @Value("${app.mail.support-email}") String supportEmail,
                      @Value("${app.mail.support-phone:}") String supportPhone,
                      @Value("${app.mail.logo:}") String logoLocation,
                      ResourceLoader resources,
                      BusinessCalendar calendar) {
        this.companyName = name(companyName, "MAIL_COMPANY_NAME (app.mail.company-name)");
        this.supportTeam = name(supportTeam, "MAIL_SUPPORT_TEAM (app.mail.support-team)");
        this.supportEmail = email(supportEmail);
        this.supportPhone = phone(supportPhone);
        this.logo = logo(logoLocation, resources);
        this.calendar = calendar;
    }

    /**
     * Writes the body and the footer into a message whose helper was created in a multipart mode, sets Reply-To to
     * the support email and adds the logo as an inline part when one is configured. From, To and the subject stay
     * with the caller.
     */
    public void write(MimeMessageHelper helper, MailBody body) throws MessagingException {
        helper.setReplyTo(supportEmail);
        // One year for both parts, even for an email written at midnight on New Year's Eve.
        int year = year();
        helper.setText(plainText(body, year), html(body, year));
        if (logo != null) {
            // After setText, as Spring advises: some mail clients resolve a cid: reference only to a later part.
            helper.addInline(LOGO_CONTENT_ID, logo.fileName(), new ByteArrayResource(logo.bytes()),
                    logo.contentType());
        }
    }

    String plainText(MailBody body) {
        return plainText(body, year());
    }

    /** Preview uses the same body and layout as SMTP, with one calendar year for both parts. */
    public Rendered render(MailBody body) {
        int year = year();
        return new Rendered(plainText(body, year), html(body, year));
    }

    public record Rendered(String plainText, String html) { }

    String html(MailBody body) {
        return html(body, year());
    }

    private String plainText(MailBody body, int year) {
        StringBuilder text = new StringBuilder(512);
        MailBody.Block previous = null;
        for (MailBody.Block block : body.blocks()) {
            if (previous != null) {
                text.append(continuesPrevious(previous, block) ? "\n" : "\n\n");
            }
            text.append(switch (block) {
                case MailBody.Paragraph paragraph -> paragraph.text();
                case MailBody.Field field -> field.label() + ": " + field.value();
                case MailBody.Link link -> link.url();
            });
            previous = block;
        }
        // "-- " (with the trailing space) is the usual signature separator: mail clients can grey out what follows.
        text.append("\n\n-- \nCần hỗ trợ? Liên hệ ").append(supportTeam).append(" qua email ").append(supportEmail);
        if (!supportPhone.isEmpty()) {
            text.append(" hoặc số điện thoại ").append(supportPhone);
        }
        text.append(".\nEmail này được gửi tự động từ ").append(companyName).append(".\n© ").append(year)
                .append(' ').append(companyName).append('\n');
        return text.toString();
    }

    private String html(MailBody body, int year) {
        StringBuilder html = new StringBuilder(4096);
        html.append("<!DOCTYPE html>\n<html lang=\"vi\">\n<head>\n<meta charset=\"UTF-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<title>").append(escape(companyName)).append("</title>\n</head>\n")
                .append("<body style=\"margin:0;padding:0;background-color:#f3f4f6;\">\n")
                .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"")
                .append(" style=\"background-color:#f3f4f6;\">\n")
                .append("<tr>\n<td align=\"center\" style=\"padding:24px 12px;\">\n")
                .append("<table role=\"presentation\" width=\"560\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"")
                .append(" style=\"width:100%;max-width:560px;background-color:#ffffff;border:1px solid #e5e7eb;")
                .append("border-radius:8px;font-family:Arial,Helvetica,sans-serif;color:#111827;\">\n")
                .append("<tr>\n<td style=\"padding:20px 24px;border-bottom:3px solid #2563eb;\">\n")
                .append(header())
                .append("\n</td>\n</tr>\n<tr>\n<td style=\"padding:24px;font-size:15px;line-height:1.6;\">\n");
        for (MailBody.Block block : body.blocks()) {
            switch (block) {
                case MailBody.Paragraph paragraph -> html.append("<p style=\"margin:0 0 16px;\">")
                        .append(escape(paragraph.text()).replace("\n", "<br>\n")).append("</p>\n");
                case MailBody.Field field -> html.append("<p style=\"margin:0 0 8px;\"><strong>")
                        .append(escape(field.label())).append(":</strong> <code style=\"font-family:Consolas,Menlo,")
                        .append("monospace;font-size:14px;background-color:#f3f4f6;padding:2px 6px;")
                        .append("border-radius:4px;\">").append(escape(field.value())).append("</code></p>\n");
                case MailBody.Link link -> {
                    String url = escape(link.url());
                    html.append("<p style=\"margin:8px 0 16px;\"><a href=\"").append(url)
                            .append("\" style=\"display:inline-block;padding:12px 24px;background-color:#2563eb;")
                            .append("color:#ffffff;text-decoration:none;font-weight:bold;border-radius:6px;\">")
                            .append(escape(link.label())).append("</a></p>\n")
                            .append("<p style=\"margin:0 0 16px;font-size:13px;color:#4b5563;\">")
                            .append("Nếu nút không mở được, hãy sao chép liên kết sau vào trình duyệt:<br>\n")
                            .append("<a href=\"").append(url)
                            .append("\" style=\"color:#2563eb;word-break:break-all;\">").append(url)
                            .append("</a></p>\n");
                }
            }
        }
        html.append("</td>\n</tr>\n<tr>\n<td style=\"padding:16px 24px;background-color:#f9fafb;")
                .append("border-top:1px solid #e5e7eb;border-radius:0 0 8px 8px;font-size:12px;line-height:1.5;")
                .append("color:#6b7280;\">\n<p style=\"margin:0 0 4px;\">Cần hỗ trợ? Liên hệ ")
                .append(escape(supportTeam)).append(" qua email <a href=\"mailto:").append(escape(supportEmail))
                .append("\" style=\"color:#2563eb;\">").append(escape(supportEmail)).append("</a>");
        if (!supportPhone.isEmpty()) {
            html.append(" hoặc số điện thoại <a href=\"tel:").append(escape(telephone(supportPhone)))
                    .append("\" style=\"color:#2563eb;\">").append(escape(supportPhone)).append("</a>");
        }
        html.append(".</p>\n<p style=\"margin:0 0 4px;\">Email này được gửi tự động từ ").append(escape(companyName))
                .append(".</p>\n<p style=\"margin:0;\">© ").append(year).append(' ').append(escape(companyName))
                .append("</p>\n</td>\n</tr>\n</table>\n</td>\n</tr>\n</table>\n</body>\n</html>\n");
        return html.toString();
    }

    private String header() {
        if (logo != null) {
            return "<img src=\"cid:" + LOGO_CONTENT_ID + "\" alt=\"" + escape(companyName) + "\" width=\"160\""
                    + " style=\"display:block;width:160px;max-width:100%;height:auto;border:0;\">";
        }
        return "<span style=\"font-size:20px;font-weight:bold;color:#1d4ed8;\">" + escape(companyName) + "</span>";
    }

    // Consecutive fields form one group of lines, and a link's URL is the last line of the paragraph introducing it.
    private static boolean continuesPrevious(MailBody.Block previous, MailBody.Block block) {
        return (previous instanceof MailBody.Field && block instanceof MailBody.Field)
                || (previous instanceof MailBody.Paragraph && block instanceof MailBody.Link);
    }

    private int year() {
        return calendar.today().getYear();
    }

    private static String escape(String value) {
        return HtmlUtils.htmlEscape(value, ENCODING);
    }

    // The tel: link keeps only the digits and '+' of the displayed number.
    private static String telephone(String phone) {
        return phone.replaceAll("[^0-9+]", "");
    }

    private static String name(String value, String setting) {
        String name = value == null ? "" : value.strip();
        int length = name.codePointCount(0, name.length());
        if (length < 1 || length > MAX_NAME_LENGTH || name.codePoints().anyMatch(MailLayout::isHidden)
                || isUtf8ReadAsIso88591(name)) {
            throw new IllegalStateException(setting + " must be 1 to " + MAX_NAME_LENGTH + " characters without"
                    + " control or format characters. In .env, which Spring reads as ISO-8859-1, write Vietnamese"
                    + " letters as Unicode escapes or leave the line out to keep the default.");
        }
        return name;
    }

    // Control characters (C0, DEL and C1), format characters such as zero-width spaces and bidi controls, line and
    // paragraph separators, lone surrogates and the replacement character left by a failed decoding.
    private static boolean isHidden(int character) {
        int type = Character.getType(character);
        return Character.isISOControl(character) || type == Character.FORMAT || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR || type == Character.SURROGATE
                || character == REPLACEMENT_CHARACTER;
    }

    // Vietnamese letters written unescaped in a UTF-8 .env, which Spring reads as ISO-8859-1, arrive as two or three
    // Latin-1 characters each ("Công" as "CÃ´ng"), often without any control character. Such text has only
    // characters up to U+00FF, some of them non-ASCII, and its ISO-8859-1 bytes are valid UTF-8. A correctly typed
    // name made of such characters ("Công ty", "Phòng Nhân") is not: there an accented letter is followed by a plain
    // letter or ends the name, while UTF-8 needs one of U+0080 to U+00BF after it.
    private static boolean isUtf8ReadAsIso88591(String name) {
        if (name.chars().allMatch(c -> c < 0x80) || name.chars().anyMatch(c -> c > 0xFF)) {
            return false;
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(name.getBytes(StandardCharsets.ISO_8859_1)));
            return true;
        } catch (CharacterCodingException exception) {
            return false;
        }
    }

    private static String email(String value) {
        String email = value == null ? "" : value.strip();
        if (email.length() > MAX_EMAIL_LENGTH || !EMAIL.matcher(email).matches()) {
            throw new IllegalStateException("MAIL_SUPPORT_EMAIL (app.mail.support-email) must be one email address"
                    + " of at most " + MAX_EMAIL_LENGTH + " characters.");
        }
        return email;
    }

    private static String phone(String value) {
        String phone = value == null ? "" : value.strip();
        if (phone.isEmpty()) {
            return phone;
        }
        long digits = phone.chars().filter(character -> character >= '0' && character <= '9').count();
        if (!PHONE.matcher(phone).matches() || digits < MIN_PHONE_DIGITS) {
            throw new IllegalStateException("MAIL_SUPPORT_PHONE (app.mail.support-phone) must be empty or a phone"
                    + " number of 6 to 20 characters: at least " + MIN_PHONE_DIGITS + " digits, spaces and + ( ) . -");
        }
        return phone;
    }

    // The logo is read and checked once, at startup, and kept in memory: every email then carries exactly the
    // checked bytes, and a file replaced later cannot change or break emails until the next restart.
    private static Logo logo(String location, ResourceLoader resources) {
        if (location == null || location.isBlank()) {
            return null;
        }
        String path = location.strip();
        if (!isLocal(path)) {
            throw new IllegalStateException(LOGO_RULE);
        }
        byte[] bytes;
        try (InputStream input = resources.getResource(path).getInputStream()) {
            bytes = input.readNBytes(MAX_LOGO_BYTES + 1);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException(LOGO_RULE, exception);
        }
        if (bytes.length <= MAX_LOGO_BYTES && startsWith(bytes, PNG)) {
            return new Logo(bytes, "image/png", "logo.png");
        }
        if (bytes.length <= MAX_LOGO_BYTES && startsWith(bytes, JPEG)) {
            return new Logo(bytes, "image/jpeg", "logo.jpg");
        }
        throw new IllegalStateException(LOGO_RULE);
    }

    // Only the class path or a file on this server: any other Spring location (https:, jar:, a path the context
    // resolves on its own, or a file: URL naming a host, which the JDK may fetch over FTP) could make the startup
    // wait on the network, as URL connections have no timeout by default.
    private static boolean isLocal(String location) {
        if (location.startsWith(ResourceUtils.CLASSPATH_URL_PREFIX)) {
            return true;
        }
        return location.startsWith(ResourceUtils.FILE_URL_PREFIX)
                && (!location.startsWith("file://") || location.startsWith("file:///"));
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        return bytes.length >= prefix.length && Arrays.equals(bytes, 0, prefix.length, prefix, 0, prefix.length);
    }

    private record Logo(byte[] bytes, String contentType, String fileName) {
    }
}
