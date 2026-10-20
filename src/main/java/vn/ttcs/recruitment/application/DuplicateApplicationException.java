package vn.ttcs.recruitment.application;

import org.springframework.http.HttpStatus;
import vn.ttcs.recruitment.common.ApiException;

import java.util.Map;

public class DuplicateApplicationException extends ApiException {
    public DuplicateApplicationException() {
        super(HttpStatus.CONFLICT, "APPLICATION_ALREADY_SUBMITTED",
                "Email này đã nộp hồ sơ cho vị trí tuyển dụng này.",
                Map.of("email", "Email này đã nộp hồ sơ cho vị trí tuyển dụng này."));
    }
}
