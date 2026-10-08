package vn.ttcs.recruitment.auth;

public class IncorrectCurrentPasswordException extends RuntimeException {
    public IncorrectCurrentPasswordException() {
        super("Mật khẩu hiện tại không chính xác.");
    }
}
