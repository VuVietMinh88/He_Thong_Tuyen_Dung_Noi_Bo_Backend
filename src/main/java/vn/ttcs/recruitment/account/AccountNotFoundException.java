package vn.ttcs.recruitment.account;

public class AccountNotFoundException extends RuntimeException {
    public AccountNotFoundException() {
        super("Không tìm thấy tài khoản.");
    }
}
