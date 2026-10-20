package vn.ttcs.recruitment.common;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * Task 339: a business error the client may retry after a number of seconds. ApiExceptionHandler returns the same
 * {code, message, fieldErrors} body as for every ApiException, plus a Retry-After header (whole seconds, at least 1)
 * and no-store. CORS exposes Retry-After, so a frontend on an allowed origin can read it.
 */
public class RetryAfterApiException extends ApiException {
    private final long retryAfterSeconds;

    public RetryAfterApiException(HttpStatus status, String code, String message, long retryAfterSeconds) {
        super(status, code, message, Map.of());
        if (retryAfterSeconds < 1) throw new IllegalArgumentException("Retry-After must be positive");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() { return retryAfterSeconds; }
}
