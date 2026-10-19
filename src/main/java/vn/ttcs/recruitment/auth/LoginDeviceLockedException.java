package vn.ttcs.recruitment.auth;

public class LoginDeviceLockedException extends RuntimeException {
    private final long retryAfterSeconds;

    public LoginDeviceLockedException(long retryAfterSeconds) {
        super("Trình duyệt này đã đăng nhập sai quá nhiều lần. Vui lòng thử lại sau.");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
