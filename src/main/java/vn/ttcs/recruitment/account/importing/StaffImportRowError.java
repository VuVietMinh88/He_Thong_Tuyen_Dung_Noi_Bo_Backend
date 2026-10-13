package vn.ttcs.recruitment.account.importing;

import org.apache.poi.ss.util.CellReference;

/**
 * One problem found in one cell of the staff sheet. A row has at most one error per column.
 *
 * @param rowNumber the row number Excel shows on the left of the sheet
 * @param column    the stable column key of {@link StaffImportColumn}, for example {@code email}
 * @param cell      the cell address administrators can type into Excel's Name Box, for example {@code A3}
 * @param code      a stable error code for the frontend, for example {@code EMAIL_INVALID}
 * @param message   a Vietnamese explanation that can be shown as it is
 */
public record StaffImportRowError(int rowNumber, String column, String cell, String code, String message) {

    static StaffImportRowError of(int rowNumber, StaffImportColumn column, String code, String message) {
        String cell = CellReference.convertNumToColString(column.ordinal()) + rowNumber;
        return new StaffImportRowError(rowNumber, column.key(), cell, code, message);
    }
}
