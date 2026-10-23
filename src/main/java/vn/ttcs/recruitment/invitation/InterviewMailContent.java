package vn.ttcs.recruitment.invitation;

import vn.ttcs.recruitment.mail.MailBody;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The subject and the body of the interview emails: an invitation, an updated schedule, a cancellation, and the notice
 * to a participant removed from an interview. Candidates and interviewers get the same text; only a candidate's
 * payload carries a reply link, and only an invitation or an update shows it.
 *
 * <p>The subject is fixed per kind and never contains a value typed by a user. The body keeps every value as raw text
 * in MailBody blocks, which MailLayout escapes; line breaks are kept only in the preparation instructions and the
 * reason, so a title cannot add lines that look like another field in the plain text. The only links are the meeting
 * link and the reply link, with HTTPS and no user info (reply links also allow loopback HTTP for local tests). A cancelled or removed slot
 * shows neither the venue nor any link, so nobody joins an old meeting or answers it.
 */
public final class InterviewMailContent {
    static final String DEFAULT_PREPARATION = "Vui lòng có mặt hoặc vào phòng họp trực tuyến trước giờ bắt đầu khoảng"
            + " 10 phút và kiểm tra lại thời gian, địa điểm hoặc liên kết phòng họp ở trên. Nếu có thắc mắc, hãy liên"
            + " hệ bộ phận tuyển dụng qua thông tin hỗ trợ ở cuối email.";
    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final String[] WEEKDAYS = {"Thứ Hai", "Thứ Ba", "Thứ Tư", "Thứ Năm", "Thứ Sáu", "Thứ Bảy",
            "Chủ Nhật"};
    // Control characters and Unicode line or paragraph separators.
    private static final Pattern BREAKS = Pattern.compile("[\\p{Cc}\\p{Zl}\\p{Zp}]+");
    private static final Pattern HIDDEN_IN_TEXT = Pattern.compile("[\\p{Cc}&&[^\\n\\t]]");
    private static final int MAX_URL_LENGTH = 2048;

    private enum Kind { INVITATION, UPDATED, CANCELLED, REMOVED }

    private InterviewMailContent() {
    }

    public static String subject(InterviewMailPayload payload) {
        return switch (kind(payload)) {
            case INVITATION -> "Thư mời phỏng vấn";
            case UPDATED -> "Cập nhật lịch phỏng vấn";
            case CANCELLED -> "Thông báo hủy lịch phỏng vấn";
            case REMOVED -> "Thông báo thay đổi người tham gia phỏng vấn";
        };
    }

    public static MailBody body(InterviewMailPayload payload, ZoneId zone) {
        return body(payload, zone, null);
    }

    /**
     * Task 533: the body with the opening of a candidate invitation taken from the INTERVIEW_INVITATION template
     * (its rendered paragraphs replace the greeting and the first sentence). The details, the venue or meeting link,
     * the preparation and the reply link stay the fixed part below it. Other kinds, and a null opening, keep the fixed
     * text, so updates, cancellations and staff emails do not change.
     */
    public static MailBody body(InterviewMailPayload payload, ZoneId zone, java.util.List<String> invitationOpening) {
        Kind kind = kind(payload);
        Objects.requireNonNull(zone, "zone");
        Instant startsAt = Objects.requireNonNull(payload.startsAt(), "startsAt");
        Instant endsAt = Objects.requireNonNull(payload.endsAt(), "endsAt");
        if (!endsAt.isAfter(startsAt)) {
            throw new IllegalArgumentException("An interview must end after it starts.");
        }
        boolean active = kind == Kind.INVITATION || kind == Kind.UPDATED;
        // Checked before anything is built: a wrong reply link is a bug of the sender, not something to send.
        String replyUrl = null;
        if (active && payload.replyUrl() != null) {
            replyUrl = replyUrl(payload.replyUrl());
            if (replyUrl == null) {
                throw new IllegalArgumentException("The reply link must be HTTPS or loopback HTTP.");
            }
        }

        String name = singleLine(payload.recipientName());
        MailBody.Builder body = MailBody.builder();
        if (kind == Kind.INVITATION && invitationOpening != null && !invitationOpening.isEmpty()) {
            invitationOpening.forEach(body::paragraph);
        } else {
            body.paragraph(name.isEmpty() ? "Xin chào," : "Xin chào " + name + ",").paragraph(switch (kind) {
                    case INVITATION -> "Bạn được mời tham gia buổi phỏng vấn với thông tin dưới đây.";
                    case UPDATED -> "Lịch phỏng vấn của bạn đã được cập nhật. Thông tin dưới đây thay thế thông tin"
                            + " trong các email trước về buổi phỏng vấn này.";
                    case CANCELLED -> "Buổi phỏng vấn dưới đây đã bị hủy. Bạn không cần tham dự và không cần phản"
                            + " hồi email này.";
                    case REMOVED -> "Bạn không còn trong danh sách tham gia buổi phỏng vấn dưới đây. Bạn không cần"
                            + " tham dự và không cần phản hồi email này.";
                });
        }

        field(body, "Vị trí", singleLine(payload.positionTitle()));
        field(body, "Vòng phỏng vấn", singleLine(payload.roundName()));
        body.field("Bắt đầu", dateTime(startsAt, zone))
                .field("Kết thúc", dateTime(endsAt, zone))
                .field("Múi giờ", zoneLabel(zone, startsAt));

        if (!active) {
            String reason = multiLine(payload.reason());
            if (!reason.isEmpty()) {
                body.paragraph("Lý do: " + reason);
            }
            body.paragraph("Vui lòng không sử dụng các liên kết trong email trước về buổi phỏng vấn này.");
            return body.build();
        }

        field(body, "Hình thức", mode(payload.mode()));
        field(body, "Địa điểm", singleLine(payload.room()));
        String meetingUrl = httpsUrl(payload.meetingUrl());
        if (meetingUrl != null) {
            body.paragraph("Tham gia phỏng vấn trực tuyến qua liên kết sau:")
                    .link("Vào phòng họp trực tuyến", meetingUrl);
        } else if (!singleLine(payload.meetingUrl()).isEmpty()) {
            body.paragraph("Liên kết phòng họp trong lịch không hợp lệ nên không được đính kèm. Vui lòng liên hệ bộ"
                    + " phận tuyển dụng để nhận liên kết.");
        }

        String preparation = multiLine(payload.preparationInstructions());
        body.paragraph("Hướng dẫn chuẩn bị:\n" + (preparation.isEmpty() ? DEFAULT_PREPARATION : preparation));

        if (replyUrl != null) {
            body.paragraph("Vui lòng xác nhận tham dự hoặc đề nghị đổi lịch qua liên kết dưới đây. Liên kết này dành"
                    + " riêng cho bạn, vui lòng không chia sẻ hoặc chuyển tiếp email này.")
                    .link("Phản hồi lời mời", replyUrl);
        }
        return body.build();
    }

    private static Kind kind(InterviewMailPayload payload) {
        Objects.requireNonNull(payload, "payload");
        String kind = Objects.requireNonNull(payload.kind(), "kind");
        try {
            return Kind.valueOf(kind);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown interview email kind.");
        }
    }

    private static void field(MailBody.Builder body, String label, String value) {
        if (!value.isEmpty()) {
            body.field(label, value);
        }
    }

    // For example "09:00 Thứ Ba, ngày 20/10/2026"; the weekday is spelled here, not taken from the JDK locale data.
    private static String dateTime(Instant instant, ZoneId zone) {
        ZonedDateTime time = instant.atZone(zone);
        return TIME.format(time) + " " + WEEKDAYS[time.getDayOfWeek().getValue() - 1] + ", ngày " + DATE.format(time);
    }

    private static String zoneLabel(ZoneId zone, Instant at) {
        ZoneOffset offset = zone.getRules().getOffset(at);
        String utc = offset.getTotalSeconds() == 0 ? "UTC" : "UTC" + offset.getId();
        if (zone.normalized().equals(VIETNAM)) {
            return "Giờ Việt Nam (" + VIETNAM.getId() + ", " + utc + ")";
        }
        return zone.getId() + " (" + utc + ")";
    }

    private static String mode(String mode) {
        String value = singleLine(mode);
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "ONLINE" -> "Trực tuyến";
            case "ONSITE", "OFFLINE", "IN_PERSON" -> "Trực tiếp";
            case "PHONE" -> "Qua điện thoại";
            default -> value;
        };
    }

    // A value shown on one line: every line break or control character becomes one space.
    private static String singleLine(String value) {
        return value == null ? "" : BREAKS.matcher(value).replaceAll(" ").strip();
    }

    // A free text that keeps its line breaks, written as \n, without any other control character.
    private static String multiLine(String value) {
        if (value == null) {
            return "";
        }
        String text = value.replace("\r\n", "\n").replace('\r', '\n').replace(' ', '\n').replace(' ', '\n');
        return HIDDEN_IN_TEXT.matcher(text).replaceAll(" ").strip();
    }

    // The URL itself when it is a printable ASCII https URL with a host and no user info, otherwise null.
    static String httpsUrl(String value) {
        if (value == null) {
            return null;
        }
        String url = value.strip();
        if (url.length() > MAX_URL_LENGTH || !url.startsWith("https://")
                || !url.chars().allMatch(character -> character > 0x20 && character < 0x7F)) {
            return null;
        }
        try {
            URI uri = new URI(url);
            if (uri.getHost() == null || uri.getRawUserInfo() != null) {
                return null;
            }
        } catch (URISyntaxException exception) {
            return null;
        }
        return url;
    }

    // Local testing uses the same backend page over loopback HTTP; remote links still require HTTPS.
    private static String replyUrl(String value) {
        String secure = httpsUrl(value);
        if (secure != null || value == null) return secure;
        try {
            if (value.length() > MAX_URL_LENGTH || !value.chars().allMatch(c -> c > 0x20 && c < 0x7f)) return null;
            URI uri = new URI(value);
            return "http".equals(uri.getScheme()) && uri.getRawUserInfo() == null
                    && java.util.Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost() == null ? "" : uri.getHost())
                    ? value : null;
        } catch (URISyntaxException failure) { return null; }
    }
}
