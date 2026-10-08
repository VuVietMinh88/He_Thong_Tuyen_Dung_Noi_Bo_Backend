package vn.ttcs.recruitment.account;

public class InvalidDepartmentException extends RuntimeException {
    public InvalidDepartmentException() {
        super("Phòng ban không tồn tại hoặc đã ngừng áp dụng.");
    }
}
