package vn.ttcs.recruitment.account.importing;

import java.util.List;
import java.util.UUID;

/**
 * The summary POST /accounts/import returns. Every filled-in row of the first sheet is in exactly one list:
 * {@code created} when its account was created and its invitation email sent, otherwise {@code skipped} together
 * with why. Both lists follow the file order, so {@code totalRows = createdCount + skippedCount}.
 *
 * @param totalRows    the number of filled-in rows read from the file, as in the preview
 * @param createdCount the number of accounts created, the size of {@code created}
 * @param skippedCount the number of rows that got no account, the size of {@code skipped}
 * @param stoppedAtRow the Excel row whose invitation email could not be sent because the mail server was not
 *                     working; the valid rows after it were not tried. Null when every valid row was tried
 */
public record StaffImportReport(
        int totalRows,
        int createdCount,
        int skippedCount,
        Integer stoppedAtRow,
        List<CreatedRow> created,
        List<SkippedRow> skipped) {

    /**
     * A row whose account was created, waiting for activation like an account made by POST /accounts.
     *
     * @param email     the email as read and normalized (trimmed, lower case)
     * @param accountId the id of the new account, usable with GET /accounts/{id}
     */
    public record CreatedRow(int rowNumber, String email, UUID accountId) {
    }

    /**
     * A row that got no account. Nothing was saved for it and no email was sent.
     *
     * @param email  the email as read and normalized; null when the cell was empty
     * @param errors why the row was skipped, never empty: the cell errors of an invalid row exactly as the preview
     *               shows them, or the one reason why a valid row could not be created
     */
    public record SkippedRow(int rowNumber, String email, List<StaffImportRowError> errors) {

        public SkippedRow {
            if (errors.isEmpty()) {
                throw new IllegalArgumentException("A skipped row needs at least one reason");
            }
            errors = List.copyOf(errors);
        }

        static SkippedRow of(StaffImportCheckedRow row, List<StaffImportRowError> errors) {
            return new SkippedRow(row.rowNumber(), row.email(), errors);
        }

        static SkippedRow of(StaffImportCheckedRow row, StaffImportRowError reason) {
            return of(row, List.of(reason));
        }
    }

    static StaffImportReport of(List<CreatedRow> created, List<SkippedRow> skipped, Integer stoppedAtRow) {
        return new StaffImportReport(created.size() + skipped.size(), created.size(), skipped.size(), stoppedAtRow,
                List.copyOf(created), List.copyOf(skipped));
    }
}
