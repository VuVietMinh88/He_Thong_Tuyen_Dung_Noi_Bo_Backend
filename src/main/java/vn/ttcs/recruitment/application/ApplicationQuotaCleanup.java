package vn.ttcs.recruitment.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ApplicationQuotaCleanup {
    private static final Logger LOG = LoggerFactory.getLogger(ApplicationQuotaCleanup.class);
    private final PostgresApplicationAttemptQuota quota;

    public ApplicationQuotaCleanup(PostgresApplicationAttemptQuota quota) { this.quota = quota; }

    @Scheduled(initialDelayString = "${app.application-intake.cleanup-delay-ms:60000}",
            fixedDelayString = "${app.application-intake.cleanup-delay-ms:60000}")
    public void clean() {
        try { quota.deleteExpired(); }
        catch (RuntimeException exception) { LOG.warn("Application quota cleanup failed; will retry on the next run"); }
    }
}
