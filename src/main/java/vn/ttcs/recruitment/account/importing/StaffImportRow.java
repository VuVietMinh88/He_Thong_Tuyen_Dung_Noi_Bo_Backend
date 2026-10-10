package vn.ttcs.recruitment.account.importing;

import java.util.List;

/**
 * One filled-in row of the staff sheet. Field names are the stable column keys of {@link StaffImportColumn}.
 * Values are normalized the same way account creation normalizes them, but not validated yet: an empty cell
 * is null (roles: an empty list), and a wrong email or role code is kept exactly as it was read.
 * {@link StaffImportValidator} checks the values afterwards.
 *
 * @param rowNumber the row number Excel shows on the left of the sheet, so administrators can find the row
 */
public record StaffImportRow(
        int rowNumber,
        String email,
        String fullName,
        List<String> roles,
        String departmentCode,
        String phone,
        String displayTitle) {
}
