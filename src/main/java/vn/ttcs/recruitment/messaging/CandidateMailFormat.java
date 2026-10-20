package vn.ttcs.recruitment.messaging;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Task 532/533: how the template variables write a time. The weekday is spelled here (not taken from the JDK locale
 * data), the same way the interview email writes its start, so an email reads the same on every JDK.
 */
public final class CandidateMailFormat {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter RECEIVED = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final String[] WEEKDAYS = {"Thứ Hai", "Thứ Ba", "Thứ Tư", "Thứ Năm", "Thứ Sáu", "Thứ Bảy",
            "Chủ Nhật"};

    private CandidateMailFormat() {
    }

    /** For example "09:00 Thứ Ba, ngày 20/10/2026" in the business zone. */
    public static String interviewTime(Instant startsAt, ZoneId zone) {
        ZonedDateTime time = startsAt.atZone(zone);
        return TIME.format(time) + " " + WEEKDAYS[time.getDayOfWeek().getValue() - 1] + ", ngày " + DATE.format(time);
    }

    /** For example "20/10/2026 09:00", the receipt's "Thời gian tiếp nhận". */
    public static String receivedAt(Instant receivedAt, ZoneId zone) {
        return RECEIVED.format(receivedAt.atZone(zone));
    }
}
