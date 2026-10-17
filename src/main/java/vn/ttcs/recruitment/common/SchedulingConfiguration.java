package vn.ttcs.recruitment.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Task 356 (story S4-08): turns on scheduled tasks, today only the daily job posting expiry (JobPostingExpiry), which
 * JobPostingExpirySchedule registers. Spring Boot then provides the scheduler (taskScheduler). A cron of "-" registers
 * no task: the tests set app.job-posting-expiry.cron to "-" (src/test/resources/application-default.properties), so no
 * run starts by itself while they move the clock. The server keeps time in UTC, so every cron runs in the business
 * zone (BusinessCalendar.zone, app.business-zone), never in the server's.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfiguration {
}
