package vn.ttcs.recruitment.account.role;

import org.springframework.http.HttpStatus;
import vn.ttcs.recruitment.common.ApiException;

// 409 ACCOUNT_ROLES_CHANGED: the roles the edit page loaded are no longer the roles of the account, so saving its set
// would silently undo another administrator's change. Nothing is written; the page reloads and the user saves again.
public class AccountRolesChangedException extends ApiException {
    public AccountRolesChangedException() {
        super(HttpStatus.CONFLICT, "ACCOUNT_ROLES_CHANGED",
                "Vai trò của tài khoản đã thay đổi (có thể người khác vừa sửa). Vui lòng tải lại trước khi lưu.");
    }
}
