package vn.ttcs.recruitment.account.importing;

import java.util.List;

/**
 * What POST /accounts/import did: every filled-in row of the first sheet, in file order, and where the import
 * stopped, if it did.
 *
 * @param rows         every filled-in row with what happened to it
 * @param stoppedAtRow the Excel row whose invitation email could not be sent because the mail server was not
 *                     working. The valid rows after it are {@code NOT_ATTEMPTED}. Null when every valid row was tried
 */
public record StaffImportResult(List<StaffImportRowResult> rows, Integer stoppedAtRow) {

    public StaffImportResult {
        rows = List.copyOf(rows);
    }
}
