package vn.ttcs.recruitment.account.importing;

import java.util.List;

/**
 * A row of the staff sheet after its values were checked: the values exactly as {@link StaffImportRow} read them,
 * whether the row can be imported, and what is wrong with it. Errors are in column order, at most one per column.
 *
 * @param valid true when the row has no error, so importing it would create an account
 */
public record StaffImportCheckedRow(
        int rowNumber,
        String email,
        String fullName,
        List<String> roles,
        String departmentCode,
        String phone,
        String displayTitle,
        boolean valid,
        List<StaffImportRowError> errors) {

    static StaffImportCheckedRow of(StaffImportRow row, List<StaffImportRowError> errors) {
        return new StaffImportCheckedRow(row.rowNumber(), row.email(), row.fullName(), row.roles(),
                row.departmentCode(), row.phone(), row.displayTitle(), errors.isEmpty(), List.copyOf(errors));
    }
}
