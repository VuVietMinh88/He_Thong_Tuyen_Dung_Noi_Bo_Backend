package vn.ttcs.recruitment.common;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * Business error for any module: ApiExceptionHandler returns its status and {code, message, fieldErrors}
 * with no-store. fieldErrors names the request fields a form should highlight; it is empty when the error
 * is not about one field.
 */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final Map<String, String> fieldErrors;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public ApiException(HttpStatus status, String code, String message, Map<String, String> fieldErrors) {
        super(message);
        this.status = status;
        this.code = code;
        this.fieldErrors = Map.copyOf(fieldErrors);
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
    public Map<String, String> getFieldErrors() { return fieldErrors; }
}
