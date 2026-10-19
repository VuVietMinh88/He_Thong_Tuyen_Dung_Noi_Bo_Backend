package vn.ttcs.recruitment.jobposting;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;
import vn.ttcs.recruitment.common.BusinessCalendar;

/**
 * Task 356 (story S4-08): the schedule of the daily job posting expiry (JobPostingExpiry.runDaily). Its cron is
 * app.job-posting-expiry.cron (a Spring cron: second minute hour day month weekday; "-" registers no task), and it runs
 * in the business time zone exactly as BusinessCalendar has read it from app.business-zone (BusinessCalendar.zone), so
 * the run and the business dates never use different zones. Not @Scheduled(zone = "${app.business-zone}"): Spring
 * reads that zone with java.util.TimeZone, which refuses offsets such as +07:00 or UTC+07:00 that java.time.ZoneId,
 * and so BusinessCalendar, accepts, and the application would not start. The trigger is built when this bean is made,
 * so an invalid cron stops the application at startup. SchedulingConfiguration (@EnableScheduling) runs it on Spring
 * Boot's task scheduler.
 */
@Component
class JobPostingExpirySchedule implements SchedulingConfigurer {
    private final JobPostingExpiry expiry;
    // null when the cron is "-" (Scheduled.CRON_DISABLED): no task.
    private final CronTrigger trigger;

    JobPostingExpirySchedule(JobPostingExpiry expiry, BusinessCalendar calendar,
                             @Value("${app.job-posting-expiry.cron}") String cron) {
        this.expiry = expiry;
        this.trigger = Scheduled.CRON_DISABLED.equals(cron) ? null : new CronTrigger(cron, calendar.zone());
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        if (trigger != null) {
            registrar.addCronTask(new CronTask(new DailyRun(expiry), trigger));
        }
    }

    // The task the scheduler runs, named JobPostingExpiry.runDaily in task listings and logs, as a @Scheduled method
    // would be.
    private record DailyRun(JobPostingExpiry expiry) implements Runnable {
        @Override
        public void run() {
            expiry.runDaily();
        }

        @Override
        public String toString() {
            return JobPostingExpiry.class.getName() + ".runDaily";
        }
    }
}
