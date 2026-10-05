package vn.ttcs.recruitment.account;

public class AccountInvitationException extends RuntimeException {
    public AccountInvitationException() {
        super("Không gửi được email kích hoạt. Tài khoản chưa được tạo; vui lòng thử lại sau.");
    }
}
