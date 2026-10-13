package vn.ttcs.recruitment.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * "Today" for business rules that compare dates, such as the needed-by date of a requisition (task 248).
 * The server keeps time in UTC (the Clock bean, and the run command sets -Duser.timezone=UTC), but the company
 * works in Vietnam: from 00:00 to 07:00 Vietnam time the UTC date is still the day before. So the date is taken in
 * the business time zone app.business-zone (application.properties, Asia/Ho_Chi_Minh by default), never in the
 * JVM default zone or the zone of the Clock. An unknown zone name stops the application at startup.
 */
@Component
public class BusinessCalendar {
    private final Clock clock;
    private final ZoneId zone;

    public BusinessCalendar(Clock clock, @Value("${app.business-zone}") ZoneId zone) {
        this.clock = clock;
        this.zone = zone;
    }

    // The clock is read on every call, so a call made after waiting for a lock past midnight gets the new date.
    // A date read earlier is not refreshed: if the caller then waits for another lock, or simply commits, after
    // midnight, the date it used is already yesterday at commit time.
    public LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), zone);
    }
}
