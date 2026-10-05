package vn.ttcs.recruitment.account;

public class InvalidAccountQueryException extends RuntimeException {
    public InvalidAccountQueryException() {
        super("Trang phải từ 0, kích thước trang từ 1 đến 100, từ khóa tối đa 255 ký tự.");
    }
}
