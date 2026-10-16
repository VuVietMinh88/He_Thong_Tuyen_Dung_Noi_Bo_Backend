package vn.ttcs.recruitment.account;

public class AccountInvitationException extends RuntimeException {
    private final boolean addressRefused;

    public AccountInvitationException() {
        this(false);
    }

    public AccountInvitationException(boolean addressRefused) {
        super("Không gửi được email kích hoạt. Tài khoản chưa được tạo; vui lòng thử lại sau.");
        this.addressRefused = addressRefused;
    }

    /**
     * True when the mail server answered and refused this one recipient address. The address is then the problem,
     * not the mail server, so emails to other addresses can still be sent. False for every other failure.
     */
    public boolean isAddressRefused() {
        return addressRefused;
    }
}
