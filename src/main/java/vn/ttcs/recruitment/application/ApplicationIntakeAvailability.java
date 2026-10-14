package vn.ttcs.recruitment.application;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import vn.ttcs.recruitment.common.ApiException;

@Component
public class ApplicationIntakeAvailability {
    private final boolean enabled;
    private final ObjectProvider<ApplicationAttemptQuota> quotas;
    private final ObjectProvider<ApplicationReceiptWriter> writers;

    public ApplicationIntakeAvailability(@Value("${app.application-intake.enabled:false}") boolean enabled,
            ObjectProvider<ApplicationAttemptQuota> quotas, ObjectProvider<ApplicationReceiptWriter> writers) {
        this.enabled = enabled;
        this.quotas = quotas;
        this.writers = writers;
    }

    public void requireReady() {
        if (!enabled || quotas.getIfAvailable() == null || writers.getIfAvailable() == null) throw unavailable();
    }

    ApplicationAttemptQuota quota() { requireReady(); return quotas.getObject(); }
    ApplicationReceiptWriter writer() { requireReady(); return writers.getObject(); }

    static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "APPLICATION_INTAKE_UNAVAILABLE",
                "Chức năng nộp hồ sơ hiện chưa sẵn sàng. Vui lòng thử lại sau.");
    }
}
