package vn.ttcs.recruitment.department;

import org.springframework.http.HttpStatus;

public class DepartmentException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public DepartmentException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
}
