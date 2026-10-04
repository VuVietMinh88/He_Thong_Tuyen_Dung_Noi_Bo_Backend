package vn.ttcs.recruitment.auth.passwordreset;

public class InvalidResetTokenException extends RuntimeException {

    public InvalidResetTokenException() {
        super("Liên kết đặt lại mật khẩu không hợp lệ hoặc đã hết hạn. Vui lòng yêu cầu liên kết mới.");
    }
}
