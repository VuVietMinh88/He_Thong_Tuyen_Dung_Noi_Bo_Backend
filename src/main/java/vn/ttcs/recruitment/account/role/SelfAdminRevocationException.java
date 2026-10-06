package vn.ttcs.recruitment.account.role;

public class SelfAdminRevocationException extends RuntimeException {
    public SelfAdminRevocationException() {
        super("Bạn không thể thu hồi vai trò ADMIN của chính mình.");
    }
}
