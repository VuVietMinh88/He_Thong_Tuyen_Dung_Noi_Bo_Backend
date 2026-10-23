package vn.ttcs.recruitment.auth.passwordreset;

import org.springframework.http.HttpStatus;
import vn.ttcs.recruitment.common.ApiException;

/** A forgot-password request without a valid X-Device-Id: it is refused before it is counted or queued. */
public class PasswordResetDeviceRequiredException extends ApiException {

    public PasswordResetDeviceRequiredException() {
        super(HttpStatus.BAD_REQUEST, "PASSWORD_RESET_DEVICE_REQUIRED",
                "Vui lòng cung cấp X-Device-Id là UUID hợp lệ của trình duyệt.");
    }
}
