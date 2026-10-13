package vn.ttcs.recruitment.account.importing;

import java.util.UUID;

/**
 * What the import did with one filled-in row of the staff sheet.
 *
 * @param rowNumber the row number Excel shows on the left of the sheet
 * @param email     the email as read and normalized (trimmed, lower case); null when the cell was empty
 * @param status    {@code CREATED} when an account was created and its invitation email sent. Otherwise nothing was
 *                  created for this row and no email was sent: {@code SKIPPED} when the row was tried or is invalid,
 *                  {@code NOT_ATTEMPTED} when the row was valid but not tried because the import stopped before it
 * @param accountId the id of the new account; null unless {@code CREATED}
 */
public record StaffImportRowResult(int rowNumber, String email, Status status, UUID accountId) {

    public enum Status { CREATED, SKIPPED, NOT_ATTEMPTED }

    static StaffImportRowResult created(StaffImportCheckedRow row, UUID accountId) {
        return new StaffImportRowResult(row.rowNumber(), row.email(), Status.CREATED, accountId);
    }

    static StaffImportRowResult skipped(StaffImportCheckedRow row) {
        return new StaffImportRowResult(row.rowNumber(), row.email(), Status.SKIPPED, null);
    }

    static StaffImportRowResult notAttempted(StaffImportCheckedRow row) {
        return new StaffImportRowResult(row.rowNumber(), row.email(), Status.NOT_ATTEMPTED, null);
    }
}
