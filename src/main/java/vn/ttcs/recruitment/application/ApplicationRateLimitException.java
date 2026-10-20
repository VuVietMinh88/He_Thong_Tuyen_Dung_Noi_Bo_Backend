package vn.ttcs.recruitment.application;

import org.springframework.http.HttpStatus;
import vn.ttcs.recruitment.common.ApiException;

public class ApplicationRateLimitException extends ApiException {
    private final long retryAfterSeconds;

    public ApplicationRateLimitException(long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, "APPLICATION_RATE_LIMIT_EXCEEDED",
                "Bạn đã gửi hồ sơ quá số lượt cho phép. Vui lòng thử lại sau.");
        if (retryAfterSeconds < 1) throw new IllegalArgumentException("Retry-After must be positive");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() { return retryAfterSeconds; }
}
