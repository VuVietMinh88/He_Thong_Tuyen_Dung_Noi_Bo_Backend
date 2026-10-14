package vn.ttcs.recruitment.jobposting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.scheduling.support.SimpleTriggerContext;
import vn.ttcs.recruitment.common.BusinessCalendar;
import vn.ttcs.recruitment.common.SchedulingConfiguration;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

// Task 356 (story S4-08): "Tạo tác vụ hàng ngày tự đánh dấu tin hết hạn". The daily expiry runs at 00:05 in the
// business time zone (Vietnam), not in the zone of the server, which keeps time in UTC: without the zone it would run
// at 07:05 in Vietnam, and postings would keep their PUBLISHED status seven hours longer than needed.
class JobPostingExpiryScheduleTest {
    private static final String CRON = "app.job-posting-expiry.cron";
    // Fires next at 00:00 on 29 Feb 2028 in the zone. Harmless even if a test here ran then: each context closes as
    // soon as its checks end, and its JobPostingExpiry has no dependencies, so a run could only fail and be logged.
    private static final String LEAP_DAY = "0 0 0 29 2 *";

    // The daily run is registered with the cron of the settings, in the business zone as BusinessCalendar reads it
    // (java.time.ZoneId): a region, or an offset such as +07:00 or UTC+07:00, which @Scheduled(zone = ...) refused at
    // startup (java.util.TimeZone does not know them). On a server clock in UTC, 00:00 on 29 Feb 2028 in UTC+7 is
    // 17:00 UTC on 28 Feb.
    @ParameterizedTest(name = "app.business-zone={0}")
    @ValueSource(strings = {"Asia/Ho_Chi_Minh", "+07:00", "UTC+07:00"})
    void theDailyRunIsRegisteredInTheBusinessZone(String zone) {
        contextWith(zone, LEAP_DAY).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(BusinessCalendar.class).zone()).isEqualTo(ZoneId.of(zone));
            assertThat(tasks(context)).singleElement().satisfies(task ->
                    assertThat(task.getTask()).isInstanceOfSatisfying(CronTask.class, cron -> {
                        assertThat(cron.getExpression()).isEqualTo(LEAP_DAY);
                        assertThat(cron.getRunnable().toString())
                                .isEqualTo("vn.ttcs.recruitment.jobposting.JobPostingExpiry.runDaily");
                        var server = new SimpleTriggerContext(
                                Clock.fixed(Instant.parse("2026-10-14T00:00:00Z"), ZoneOffset.UTC));
                        assertThat(cron.getTrigger().nextExecution(server))
                                .isEqualTo(Instant.parse("2028-02-28T17:00:00Z"));
                    }));
        });
    }

    // "-" turns the run off: no task, and the application starts.
    @Test
    void aCronOfDashRegistersNoTask() {
        contextWith("+07:00", "-").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(tasks(context)).isEmpty();
        });
    }

    // A cron with a missing field, or an empty one (a line JOB_POSTING_EXPIRY_CRON= in .env), stops the application at
    // startup instead of never running.
    @ParameterizedTest(name = "cron \"{0}\"")
    @ValueSource(strings = {"0 5 0 * *", ""})
    void anInvalidCronStopsTheApplicationAtStartup(String cron) {
        contextWith("Asia/Ho_Chi_Minh", cron).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().isInstanceOf(IllegalArgumentException.class);
        });
    }

    // The main settings (src/test has no application.properties of its own): 00:05 every day, which the environment
    // variable JOB_POSTING_EXPIRY_CRON may replace, in Asia/Ho_Chi_Minh.
    @Test
    void theDefaultIsFiveMinutesPastMidnightInVietnam() throws Exception {
        Properties settings = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));
        String cron = settings.getProperty(CRON);
        assertThat(cron).isEqualTo("${JOB_POSTING_EXPIRY_CRON:0 5 0 * * *}");
        String fallback = cron.substring(cron.indexOf(':') + 1, cron.length() - 1);
        assertThat(fallback).isEqualTo("0 5 0 * * *");
        ZoneId zone = ZoneId.of(settings.getProperty("app.business-zone"));
        assertThat(zone).isEqualTo(ZoneId.of("Asia/Ho_Chi_Minh"));

        // 23:59 on the deadline day in Vietnam: the next run is 00:05 the day after, 17:05 UTC on the deadline day.
        ZonedDateTime lastMinute = ZonedDateTime.parse("2026-11-30T23:59:00+07:00[Asia/Ho_Chi_Minh]");
        assertThat(CronExpression.parse(fallback).next(lastMinute))
                .isEqualTo(ZonedDateTime.parse("2026-12-01T00:05:00+07:00[Asia/Ho_Chi_Minh]"));
        // The trigger JobPostingExpirySchedule builds from these settings, on a server clock in UTC.
        Clock server = Clock.fixed(Instant.parse("2026-11-30T16:59:00Z"), ZoneOffset.UTC);
        assertThat(new CronTrigger(fallback, zone).nextExecution(new SimpleTriggerContext(server)))
                .isEqualTo(Instant.parse("2026-11-30T17:05:00Z"));
        // Without the zone it would follow the server clock: 00:05 UTC, already 07:05 in Vietnam.
        assertThat(new CronTrigger(fallback).nextExecution(new SimpleTriggerContext(server)))
                .isEqualTo(Instant.parse("2026-12-01T00:05:00Z"));
    }

    // Scheduling on (SchedulingConfiguration), the business calendar, the schedule and the real JobPostingExpiry class,
    // so a @Scheduled method on it would be processed too. Its dependencies are left out: no task runs here.
    private static ApplicationContextRunner contextWith(String zone, String cron) {
        return new ApplicationContextRunner()
                .withPropertyValues("app.business-zone=" + zone, CRON + "=" + cron)
                .withBean(Clock.class, Clock::systemUTC)
                .withBean(JobPostingExpiry.class, () -> new JobPostingExpiry(null, null, null, null, null, null))
                .withUserConfiguration(SchedulingConfiguration.class, BusinessCalendar.class,
                        JobPostingExpirySchedule.class);
    }

    private static List<ScheduledTask> tasks(ApplicationContext context) {
        List<ScheduledTask> tasks = new ArrayList<>();
        context.getBeansOfType(ScheduledTaskHolder.class).values()
                .forEach(holder -> tasks.addAll(holder.getScheduledTasks()));
        return tasks;
    }
}
