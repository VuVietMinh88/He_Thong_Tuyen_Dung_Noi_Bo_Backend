package vn.ttcs.recruitment.account.importing;

import org.apache.poi.ss.util.CellReference;

import static vn.ttcs.recruitment.account.importing.StaffImportColumn.DEPARTMENT_CODE;
import static vn.ttcs.recruitment.account.importing.StaffImportColumn.EMAIL;

/**
 * One problem of one row of the staff sheet: a wrong cell value found by {@link StaffImportValidator}, or the
 * reason why the import did not create the account of a valid row. A row has at most one error per column.
 *
 * @param rowNumber the row number Excel shows on the left of the sheet
 * @param column    the stable column key of {@link StaffImportColumn}, for example {@code email}; null when the
 *                  reason is not in any cell (the mail server was not working, or the row was not tried)
 * @param cell      the cell address administrators can type into Excel's Name Box, for example {@code A3}; null
 *                  together with {@code column}
 * @param code      a stable error code for the frontend, for example {@code EMAIL_INVALID}
 * @param message   a Vietnamese explanation that can be shown as it is
 */
public record StaffImportRowError(int rowNumber, String column, String cell, String code, String message) {

    static StaffImportRowError of(int rowNumber, StaffImportColumn column, String code, String message) {
        String cell = CellReference.convertNumToColString(column.ordinal()) + rowNumber;
        return new StaffImportRowError(rowNumber, column.key(), cell, code, message);
    }

    // The reasons below explain why a row that was valid when the import checked it still got no account.
    // Codes that also exist elsewhere keep their meaning: EMAIL_ALREADY_EXISTS as in the row check and
    // POST /accounts, INVALID_DEPARTMENT as in PUT /accounts/{id}, ACCOUNT_EMAIL_UNAVAILABLE as in POST /accounts.

    static StaffImportRowError emailTaken(int rowNumber) {
        return of(rowNumber, EMAIL, "EMAIL_ALREADY_EXISTS", "Email vừa được dùng cho một tài khoản khác trong lúc "
                + "nhập, nên không tạo tài khoản mới cho dòng này.");
    }

    static StaffImportRowError departmentUnavailable(int rowNumber, String departmentCode) {
        return of(rowNumber, DEPARTMENT_CODE, "INVALID_DEPARTMENT", "Phòng ban mã \"" + departmentCode
                + "\" vừa ngừng áp dụng hoặc đổi mã trong lúc nhập, nên chưa tạo tài khoản cho dòng này.");
    }

    static StaffImportRowError addressRefused(int rowNumber) {
        return of(rowNumber, EMAIL, "EMAIL_ADDRESS_REFUSED", "Máy chủ thư từ chối địa chỉ email này nên không "
                + "gửi được email mời; chưa tạo tài khoản. Hãy kiểm tra lại email.");
    }

    static StaffImportRowError mailServerUnavailable(int rowNumber) {
        return new StaffImportRowError(rowNumber, null, null, "ACCOUNT_EMAIL_UNAVAILABLE", "Máy chủ thư không "
                + "hoạt động nên không gửi được email mời; chưa tạo tài khoản. Việc nhập dừng ở dòng này; hãy nhập "
                + "lại tệp khi máy chủ thư hoạt động.");
    }

    static StaffImportRowError notAttempted(int rowNumber, int stoppedAtRow) {
        return new StaffImportRowError(rowNumber, null, null, "NOT_ATTEMPTED", "Dòng hợp lệ nhưng chưa được nhập "
                + "vì việc nhập đã dừng ở dòng " + stoppedAtRow + " do máy chủ thư không hoạt động. Nhập lại tệp "
                + "khi máy chủ thư hoạt động để tạo tài khoản này.");
    }
}
