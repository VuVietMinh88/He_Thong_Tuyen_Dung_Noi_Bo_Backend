package vn.ttcs.recruitment.reporting;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An inclusive range of business dates a report covers. Both ends are given or both are left out (then the report's
 * default applies); at most 366 days; dates between 2000-01-01 and 2100-12-31. Timestamps are compared with the
 * half-open interval [start of from, start of the day after to) in the business time zone (BusinessCalendar).
 */
record ReportPeriod(LocalDate from, LocalDate to) {
    static final int MAX_DAYS = 366;
    static final LocalDate EARLIEST = LocalDate.of(2000, 1, 1);
    static final LocalDate LATEST = LocalDate.of(2100, 12, 31);

    /** The given range, or null when both ends are left out; 400 VALIDATION_ERROR for an invalid range. */
    static ReportPeriod explicit(LocalDate from, LocalDate to) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (from == null && to == null) {
            return null;
        }
        if (from == null || to == null) {
            errors.put(from == null ? "from" : "to", "Phải nhập cả ngày bắt đầu (from) và ngày kết thúc (to), hoặc bỏ trống cả hai.");
            throw ReportPaging.invalid(errors);
        }
        if (from.isBefore(EARLIEST) || from.isAfter(LATEST)) {
            errors.put("from", "Ngày phải từ 2000-01-01 đến 2100-12-31.");
        }
        if (to.isBefore(EARLIEST) || to.isAfter(LATEST)) {
            errors.put("to", "Ngày phải từ 2000-01-01 đến 2100-12-31.");
        }
        if (errors.isEmpty() && to.isBefore(from)) {
            errors.put("to", "Ngày kết thúc phải bằng hoặc sau ngày bắt đầu.");
        }
        if (errors.isEmpty() && ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            errors.put("to", "Khoảng thời gian tối đa là " + MAX_DAYS + " ngày.");
        }
        if (!errors.isEmpty()) {
            throw ReportPaging.invalid(errors);
        }
        return new ReportPeriod(from, to);
    }

    /** The given range, or the defaultDays days ending today when both ends are left out. */
    static ReportPeriod resolve(LocalDate from, LocalDate to, LocalDate today, int defaultDays) {
        ReportPeriod given = explicit(from, to);
        return given != null ? given : new ReportPeriod(today.minusDays(defaultDays - 1L), today);
    }
}
