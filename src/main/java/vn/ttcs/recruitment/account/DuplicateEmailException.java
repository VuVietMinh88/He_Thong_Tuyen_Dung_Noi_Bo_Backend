package vn.ttcs.recruitment.account;

public class DuplicateEmailException extends RuntimeException {
    public DuplicateEmailException() {
        super("Email đã được sử dụng cho một tài khoản nội bộ.");
    }
}
