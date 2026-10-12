package vn.ttcs.recruitment.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import vn.ttcs.recruitment.auth.AuthenticationFailureException;
import vn.ttcs.recruitment.auth.IncorrectCurrentPasswordException;
import vn.ttcs.recruitment.auth.passwordreset.InvalidResetTokenException;
import vn.ttcs.recruitment.account.DuplicateEmailException;
import vn.ttcs.recruitment.account.AccountInvitationException;
import vn.ttcs.recruitment.account.InvalidActivationTokenException;
import vn.ttcs.recruitment.account.AccountNotFoundException;
import vn.ttcs.recruitment.account.InvalidDepartmentException;
import vn.ttcs.recruitment.account.InvalidAccountQueryException;
import vn.ttcs.recruitment.account.role.SelfAdminRevocationException;
import vn.ttcs.recruitment.account.lock.SelfAccountLockException;
import vn.ttcs.recruitment.department.DepartmentException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> apiFailure(ApiException exception) {
        return ResponseEntity.status(exception.getStatus()).cacheControl(CacheControl.noStore())
                .body(new ApiError(exception.getCode(), exception.getMessage(), exception.getFieldErrors()));
    }

    @ExceptionHandler(DepartmentException.class)
    public ResponseEntity<ApiError> departmentFailure(DepartmentException exception) {
        return ResponseEntity.status(exception.getStatus()).cacheControl(CacheControl.noStore())
                .body(ApiError.of(exception.getCode(), exception.getMessage()));
    }

    @ExceptionHandler(SelfAccountLockException.class)
    public ResponseEntity<ApiError> selfAccountLock(SelfAccountLockException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).cacheControl(CacheControl.noStore())
                .body(ApiError.of("SELF_ACCOUNT_LOCK", exception.getMessage()));
    }

    @ExceptionHandler(SelfAdminRevocationException.class)
    public ResponseEntity<ApiError> selfAdminRevocation(SelfAdminRevocationException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).cacheControl(CacheControl.noStore())
                .body(ApiError.of("SELF_ADMIN_REVOCATION", exception.getMessage()));
    }

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<ApiError> accountNotFound(AccountNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).cacheControl(CacheControl.noStore())
                .body(ApiError.of("ACCOUNT_NOT_FOUND", exception.getMessage()));
    }

    @ExceptionHandler(InvalidDepartmentException.class)
    public ResponseEntity<ApiError> invalidDepartment(InvalidDepartmentException exception) {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                .body(ApiError.of("INVALID_DEPARTMENT", exception.getMessage()));
    }

    @ExceptionHandler(InvalidAccountQueryException.class)
    public ResponseEntity<ApiError> invalidQuery(InvalidAccountQueryException exception) {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                .body(ApiError.of("VALIDATION_ERROR", exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> invalidParameter() {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                .body(ApiError.of("VALIDATION_ERROR", "Tham số đường dẫn hoặc bộ lọc không hợp lệ."));
    }

    @ExceptionHandler(DuplicateEmailException.class)
    public ResponseEntity<ApiError> duplicateEmail(DuplicateEmailException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).cacheControl(CacheControl.noStore())
                .body(ApiError.of("EMAIL_ALREADY_EXISTS", exception.getMessage()));
    }

    @ExceptionHandler(AccountInvitationException.class)
    public ResponseEntity<ApiError> invitationFailed(AccountInvitationException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore())
                .body(ApiError.of("ACCOUNT_EMAIL_UNAVAILABLE", exception.getMessage()));
    }

    @ExceptionHandler(InvalidActivationTokenException.class)
    public ResponseEntity<ApiError> invalidActivation(InvalidActivationTokenException exception) {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                .body(ApiError.of("ACTIVATION_TOKEN_INVALID", exception.getMessage()));
    }

    @ExceptionHandler(IncorrectCurrentPasswordException.class)
    public ResponseEntity<ApiError> incorrectCurrentPassword(IncorrectCurrentPasswordException exception) {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                .body(ApiError.of("CURRENT_PASSWORD_INCORRECT", exception.getMessage()));
    }


    @ExceptionHandler(InvalidResetTokenException.class)
    public ResponseEntity<ApiError> invalidResetToken(InvalidResetTokenException exception) {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                .body(ApiError.of("RESET_TOKEN_INVALID", exception.getMessage()));
    }

    @ExceptionHandler(AuthenticationFailureException.class)
    public ResponseEntity<ApiError> authenticationFailure(AuthenticationFailureException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiError.of(exception.getCode(), exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validationFailure(MethodArgumentNotValidException exception) {
        Map<String, String> errors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors()
                .forEach(error -> errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return ResponseEntity.badRequest().body(new ApiError(
                "VALIDATION_ERROR", "Vui lòng kiểm tra dữ liệu đã nhập.", errors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> invalidJson() {
        return ResponseEntity.badRequest().body(ApiError.of("INVALID_JSON", "Nội dung yêu cầu không đúng định dạng JSON."));
    }
}
