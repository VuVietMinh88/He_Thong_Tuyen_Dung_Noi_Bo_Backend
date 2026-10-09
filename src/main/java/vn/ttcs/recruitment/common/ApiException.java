package vn.ttcs.recruitment.common;

import org.springframework.http.HttpStatus;

/** Business error for any module: ApiExceptionHandler returns its status and {code, message} with no-store. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
}
