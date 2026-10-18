package vn.ttcs.recruitment.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Task 339: removes idle tracking buckets every minute (V31), like ApplicationQuotaCleanup does for the intake's.
@Component
public class ApplicationTrackingQuotaCleanup {
    private static final Logger LOG = LoggerFactory.getLogger(ApplicationTrackingQuotaCleanup.class);
    private final PostgresApplicationTrackingQuota quota;

    public ApplicationTrackingQuotaCleanup(PostgresApplicationTrackingQuota quota) { this.quota = quota; }

    @Scheduled(initialDelayString = "${app.application-tracking.cleanup-delay-ms:60000}",
            fixedDelayString = "${app.application-tracking.cleanup-delay-ms:60000}")
    public void clean() {
        try { quota.deleteExpired(); }
        catch (RuntimeException exception) { LOG.warn("Application tracking quota cleanup failed; will retry on the next run"); }
    }
}
