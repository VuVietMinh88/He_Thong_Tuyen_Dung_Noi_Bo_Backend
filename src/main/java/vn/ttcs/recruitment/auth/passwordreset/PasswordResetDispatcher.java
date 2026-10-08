package vn.ttcs.recruitment.auth.passwordreset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

@Component
public class PasswordResetDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(PasswordResetDispatcher.class);
    private final ThreadPoolTaskExecutor executor;
    private final PasswordResetService service;

    public PasswordResetDispatcher(@Qualifier("passwordResetExecutor") ThreadPoolTaskExecutor executor,
                                   PasswordResetService service) {
        this.executor = executor;
        this.service = service;
    }

    public void request(String email) {
        // Account lookup and SMTP happen after queuing, on the same path for every valid email input.
        executor.execute(() -> {
            try {
                service.sendResetEmail(email);
            } catch (RuntimeException exception) {
                // Mail/SQL exception bodies can contain addresses or tokens; log only the error type.
                LOG.warn("Password reset email processing failed ({})", exception.getClass().getSimpleName());
            }
        });
    }
}
