package vn.ttcs.recruitment.offer;

import vn.ttcs.recruitment.mail.MailBody;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The subject and the body of the offer email. A preview and the email that is sent have the same blocks; only the
 * sent email has the reply link, right after the sentence asking the candidate to accept or decline.
 *
 * <p>The subject is fixed and never contains a value typed by a user. The body keeps every value as raw text in
 * MailBody blocks, which MailLayout escapes; every value is shown on one line, so a name or a title cannot add lines
 * that look like another field in the plain text. Amounts are written here with Vietnamese digit grouping instead of
 * the JDK locale data, so the text does not change with the JDK. The body states only the terms of the payload: no
 * company name, no contract wording and nothing about tax.
 *
 * <p>The reply link carries its token in the fragment, which browsers do not send to servers or proxies. It must be
 * HTTPS (or loopback HTTP for local tests), printable ASCII, at most 2048 characters, with a valid host and no user
 * info; any other link is a bug of the sender and is rejected rather than sent.
 */
public final class OfferMailContent {
    static final long MAX_AMOUNT = 1_000_000_000_000L;
    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final String[] WEEKDAYS = {"Thứ Hai", "Thứ Ba", "Thứ Tư", "Thứ Năm", "Thứ Sáu", "Thứ Bảy",
            "Chủ Nhật"};
    // Control characters, invisible format characters (such as bidi overrides) and Unicode line or paragraph separators.
    private static final Pattern BREAKS = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]+");
    private static final Pattern LABEL = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?");
    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]");
    private static final int MAX_URL_LENGTH = 2048;
    private static final int MAX_HOST_LENGTH = 253;

    private OfferMailContent() {
    }

    public static String subject(OfferMailPayload payload) {
        Objects.requireNonNull(payload, "payload");
        return "Thư mời nhận việc";
    }

    public static MailBody body(OfferMailPayload payload, ZoneId zone) {
        return body(payload, zone, null);
    }

    /**
     * Task 533: the body with its opening (greeting and first sentence) taken from the OFFER template's rendered
     * paragraphs. The approved terms, the reply link and the closing stay fixed below it; a null opening keeps the
     * fixed text, as the offer preview does.
     */
    public static MailBody body(OfferMailPayload payload, ZoneId zone, java.util.List<String> opening) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(zone, "zone");
        LocalDate startDate = Objects.requireNonNull(payload.startDate(), "startDate");
        Instant deadline = Objects.requireNonNull(payload.responseDeadline(), "responseDeadline");
        String position = singleLine(payload.positionTitle());
        if (position.isEmpty()) {
            throw new IllegalArgumentException("An offer needs a position title.");
        }
        String salary = money(payload.salary(), "salary");
        String allowance = money(payload.allowance(), "allowance");
        // Checked before anything is built: a wrong reply link is a bug of the sender, not something to send.
        String replyUrl = null;
        if (payload.replyUrl() != null) {
            replyUrl = replyUrl(payload.replyUrl());
            if (replyUrl == null) {
                throw new IllegalArgumentException("The reply link must be a valid HTTPS or loopback HTTP URL with"
                        + " its token in the fragment.");
            }
        }

        String name = singleLine(payload.recipientName());
        MailBody.Builder body = MailBody.builder();
        if (opening != null && !opening.isEmpty()) {
            opening.forEach(body::paragraph);
        } else {
            body.paragraph(name.isEmpty() ? "Xin chào," : "Xin chào " + name + ",")
                    .paragraph("Bạn nhận được lời mời nhận việc với thông tin dưới đây.");
        }
        body.field("Vị trí", position)
                .field("Lương cơ bản hằng tháng", salary)
                .field("Phụ cấp hằng tháng", allowance)
                .field("Ngày bắt đầu làm việc", date(startDate))
                .field("Hạn phản hồi", dateTime(deadline, zone) + " (" + zoneLabel(zone, deadline) + ")")
                .paragraph("Lương cơ bản và phụ cấp ở trên là số tiền theo tháng, tính bằng đồng Việt Nam (VND).")
                .paragraph("Vui lòng cho biết bạn chấp nhận hoặc từ chối lời mời nhận việc này trước hạn phản hồi"
                        + " ở trên.");
        if (replyUrl != null) {
            body.link("Chấp nhận hoặc từ chối lời mời", replyUrl);
        }
        body.paragraph("Nếu có thắc mắc, hãy liên hệ bộ phận tuyển dụng qua thông tin hỗ trợ ở cuối email. Vui lòng"
                + " không chuyển tiếp email này cho người khác.");
        return body.build();
    }

    // For example "15.000.000 VND": whole VND with a dot between groups of three digits.
    static String money(long amount, String name) {
        if (amount < 0 || amount > MAX_AMOUNT) {
            throw new IllegalArgumentException("The " + name + " must be a whole monthly VND amount from 0 to "
                    + MAX_AMOUNT + ".");
        }
        String digits = Long.toString(amount);
        StringBuilder text = new StringBuilder(digits.length() + 8);
        for (int index = 0; index < digits.length(); index++) {
            if (index > 0 && (digits.length() - index) % 3 == 0) {
                text.append('.');
            }
            text.append(digits.charAt(index));
        }
        return text.append(" VND").toString();
    }

    // For example "Thứ Hai, ngày 02/11/2026"; the weekday is spelled here, not taken from the JDK locale data.
    private static String date(LocalDate date) {
        return WEEKDAYS[date.getDayOfWeek().getValue() - 1] + ", ngày " + DATE.format(date);
    }

    // For example "17:00 Thứ Sáu, ngày 30/10/2026".
    private static String dateTime(Instant instant, ZoneId zone) {
        ZonedDateTime time = instant.atZone(zone);
        return TIME.format(time) + " " + date(time.toLocalDate());
    }

    private static String zoneLabel(ZoneId zone, Instant at) {
        ZoneOffset offset = zone.getRules().getOffset(at);
        String utc = offset.getTotalSeconds() == 0 ? "UTC" : "UTC" + offset.getId();
        if (zone.normalized().equals(VIETNAM)) {
            return "Giờ Việt Nam, " + VIETNAM.getId() + ", " + utc;
        }
        // A fixed offset has no region name worth showing: "Z" or "+07:00" would only repeat the offset.
        return zone instanceof ZoneOffset ? utc : zone.getId() + ", " + utc;
    }

    // A value shown on one line: every line break, control or format character becomes one space.
    private static String singleLine(String value) {
        return value == null ? "" : BREAKS.matcher(value).replaceAll(" ").strip();
    }

    // The URL itself when it is a valid reply link, otherwise null. Nothing is stripped: a link with spaces or control
    // characters around it is as wrong as one with them inside.
    static String replyUrl(String url) {
        if (url == null || url.isEmpty() || url.length() > MAX_URL_LENGTH
                || !url.chars().allMatch(character -> character > 0x20 && character < 0x7F)) {
            return null;
        }
        URI uri;
        try {
            uri = new URI(url).parseServerAuthority();
        } catch (URISyntaxException exception) {
            return null;
        }
        String host = uri.getHost();
        if (host == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || !validHost(host)
                || (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65_535))) {
            return null;
        }
        String fragment = uri.getRawFragment();
        if (fragment == null || fragment.isEmpty()) {
            return null;
        }
        // Local testing uses the same page over loopback HTTP; remote links still require HTTPS.
        boolean secure = url.startsWith("https://");
        boolean loopback = url.startsWith("http://") && LOOPBACK.contains(host.toLowerCase(Locale.ROOT));
        return secure || loopback ? url : null;
    }

    // A DNS name of letter-digit-hyphen labels, a dotted IPv4 address, or an IPv6 literal already checked by URI.
    private static boolean validHost(String host) {
        if (host.isEmpty() || host.length() > MAX_HOST_LENGTH) {
            return false;
        }
        if (host.startsWith("[")) {
            return host.endsWith("]");
        }
        String[] labels = host.split("\\.", -1);
        for (String label : labels) {
            if (!LABEL.matcher(label).matches()) {
                return false;
            }
        }
        if (!isNumber(labels[labels.length - 1])) {
            return true;
        }
        // A numeric last label only makes sense in an IPv4 address such as 127.0.0.1.
        if (labels.length != 4) {
            return false;
        }
        for (String label : labels) {
            if (!isNumber(label) || label.length() > 3 || Integer.parseInt(label) > 255) {
                return false;
            }
        }
        return true;
    }

    private static boolean isNumber(String label) {
        return !label.isEmpty() && label.chars().allMatch(character -> character >= '0' && character <= '9');
    }
}
