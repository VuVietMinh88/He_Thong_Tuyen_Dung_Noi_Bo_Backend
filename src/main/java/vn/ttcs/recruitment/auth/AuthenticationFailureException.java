package vn.ttcs.recruitment.auth;

public class AuthenticationFailureException extends RuntimeException {

    private final String code;

    private AuthenticationFailureException(String code, String message) {
        super(message);
        this.code = code;
    }

    public static AuthenticationFailureException loginFailed() {
        return new AuthenticationFailureException("LOGIN_FAILED", "Không thể đăng nhập bằng thông tin đã cung cấp.");
    }

    public static AuthenticationFailureException sessionInvalid() {
        return new AuthenticationFailureException("SESSION_INVALID", "Phiên đăng nhập không hợp lệ hoặc đã hết hạn. Vui lòng đăng nhập lại.");
    }

    public String getCode() {
        return code;
    }
}
