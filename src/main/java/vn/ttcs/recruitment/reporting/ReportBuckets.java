package vn.ttcs.recruitment.reporting;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

/**
 * Task 556: whole business weeks (Monday to Sunday) and whole calendar months that cover a date range, so a chart has
 * one point per week or month, zero included. SQL then counts the timestamps of each bucket with width_bucket over the
 * moments these days start in the business time zone; the zone never enters SQL (BusinessCalendar explains why).
 */
final class ReportBuckets {
    private ReportBuckets() {
    }

    record Bucket(LocalDate start, LocalDate end) {
        LocalDate dayAfter() {
            return end.plusDays(1);
        }
    }

    static List<Bucket> weeks(LocalDate from, LocalDate to) {
        List<Bucket> buckets = new ArrayList<>();
        for (LocalDate start = from.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)); !start.isAfter(to);
             start = start.plusWeeks(1)) {
            buckets.add(new Bucket(start, start.plusDays(6)));
        }
        return buckets;
    }

    static List<Bucket> months(LocalDate from, LocalDate to) {
        List<Bucket> buckets = new ArrayList<>();
        for (LocalDate start = from.withDayOfMonth(1); !start.isAfter(to); start = start.plusMonths(1)) {
            buckets.add(new Bucket(start, start.plusMonths(1).minusDays(1)));
        }
        return buckets;
    }
}
