package vn.ttcs.recruitment.account;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import vn.ttcs.recruitment.common.ApiException;

import java.util.Map;

/**
 * 409 PHONE_ALREADY_EXISTS: the phone number already belongs to another internal account, whatever that account's
 * status (active, pending activation, locked or disabled). V38 makes {@code user_accounts.phone} unique. Every write
 * path checks first, so the usual case writes nothing, and translates the constraint with {@link #translate} for two
 * saves that race after the check. ApiExceptionHandler answers with {code, message, fieldErrors} and no-store, and
 * fieldErrors.phone lets a form show the message under the phone field. Neither message names the account that has
 * the number.
 */
public class PhoneAlreadyUsedException extends ApiException {
    public static final String CODE = "PHONE_ALREADY_EXISTS";
    public static final String CONSTRAINT = "user_accounts_phone_key";

    public PhoneAlreadyUsedException() {
        super(HttpStatus.CONFLICT, CODE, "Số điện thoại đã được sử dụng cho một tài khoản khác.",
                Map.of("phone", "Số điện thoại này đã được dùng cho một tài khoản khác. Vui lòng nhập số khác."));
    }

    /** Whether the failure, or any of its causes, is the unique violation of V38's phone constraint. */
    public static boolean isPhoneConstraint(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && "23505".equals(violation.getSQLState())
                    && CONSTRAINT.equals(violation.getConstraintName())) {
                return true;
            }
        }
        return false;
    }

    /** The 409 for a write that broke the phone constraint; any other failure is returned unchanged. */
    public static RuntimeException translate(DataIntegrityViolationException exception) {
        return isPhoneConstraint(exception) ? new PhoneAlreadyUsedException() : exception;
    }
}
