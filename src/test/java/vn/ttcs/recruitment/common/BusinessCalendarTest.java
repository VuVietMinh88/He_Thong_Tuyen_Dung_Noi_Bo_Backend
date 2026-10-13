package vn.ttcs.recruitment.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

// Task 248: "today" is the date in the configured business time zone. The clock below deliberately carries another
// zone (UTC+14), so the test fails if BusinessCalendar used the clock's zone or the JVM default zone instead.
class BusinessCalendarTest {
    private static final ZoneId CLOCK_ZONE = ZoneId.of("Pacific/Kiritimati");

    @ParameterizedTest(name = "{0} in {1} is {2}")
    @CsvSource({
            // 23:59:59 on 6 Oct in Vietnam (UTC+7); UTC is on 6 Oct as well.
            "2026-10-06T16:59:59Z, Asia/Ho_Chi_Minh, 2026-10-06",
            // 00:00 on 7 Oct in Vietnam while UTC is still on 6 Oct.
            "2026-10-06T17:00:00Z, Asia/Ho_Chi_Minh, 2026-10-07",
            // The example of the task: 00:30 on 7 Oct in Vietnam.
            "2026-10-06T17:30:00Z, Asia/Ho_Chi_Minh, 2026-10-07",
            // Midnight UTC changes nothing in Vietnam: it is 07:00 on 7 Oct there.
            "2026-10-07T00:00:00Z, Asia/Ho_Chi_Minh, 2026-10-07",
            // The same instant in other zones gives other dates: the configured zone decides.
            "2026-10-06T17:30:00Z, UTC, 2026-10-06",
            "2026-10-07T03:00:00Z, America/New_York, 2026-10-06"
    })
    void todayIsTheDateOfTheClockInstantInTheBusinessZone(Instant now, String zone, LocalDate expected) {
        var calendar = new BusinessCalendar(Clock.fixed(now, CLOCK_ZONE), ZoneId.of(zone));

        assertThat(calendar.today()).isEqualTo(expected);
    }

    // The zone comes from the property app.business-zone; an offset such as +07:00 is accepted too.
    @Test
    void theZoneIsReadFromTheApplicationProperty() {
        contextWith("+07:00").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(BusinessCalendar.class).today()).isEqualTo(LocalDate.of(2026, 10, 7));
        });
    }

    // A typing mistake in the zone name stops the application at startup instead of silently using another day.
    @Test
    void anUnknownZoneNameStopsTheApplicationAtStartup() {
        contextWith("Asia/Ha_Noi").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("Asia/Ha_Noi");
        });
    }

    // Only BusinessCalendar and a clock at 00:00 on 7 Oct in Vietnam (17:00 on 6 Oct in UTC).
    private static ApplicationContextRunner contextWith(String zone) {
        return new ApplicationContextRunner()
                .withPropertyValues("app.business-zone=" + zone)
                .withBean(Clock.class, () -> Clock.fixed(Instant.parse("2026-10-06T17:00:00Z"), CLOCK_ZONE))
                .withUserConfiguration(BusinessCalendar.class);
    }
}
