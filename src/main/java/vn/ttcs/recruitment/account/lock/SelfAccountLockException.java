package vn.ttcs.recruitment.account.lock;

public class SelfAccountLockException extends RuntimeException {
    public SelfAccountLockException() {
        super("Bạn không thể tự khóa tài khoản của chính mình.");
    }
}
