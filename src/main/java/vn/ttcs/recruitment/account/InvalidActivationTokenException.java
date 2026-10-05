package vn.ttcs.recruitment.account;

public class InvalidActivationTokenException extends RuntimeException {
    public InvalidActivationTokenException() {
        super("Liên kết kích hoạt không hợp lệ, đã hết hạn hoặc đã được sử dụng.");
    }
}
