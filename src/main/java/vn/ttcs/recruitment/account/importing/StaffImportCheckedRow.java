package vn.ttcs.recruitment.account.importing;

import java.util.List;

/**
 * A row of the staff sheet after its values were checked: the values exactly as {@link StaffImportRow} read them,
 * whether the row can be imported, what is wrong with it and what the administrator should look at before
 * importing it. Errors and warnings are each in column order, at most one per column.
 *
 * @param valid    true when the row has no error, so importing it would create an account
 * @param errors   the problems that stop the row from being imported; empty, never null, when the row is valid
 * @param warnings values that may be a mistake but do not stop the import, for example a name that another
 *                 account already has: two people may have the same name. Warnings never make a row invalid; at
 *                 most one per column; empty, never null, when there is nothing to look at
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
        List<StaffImportRowError> errors,
        List<StaffImportRowError> warnings) {

    static StaffImportCheckedRow of(StaffImportRow row, List<StaffImportRowError> errors,
                                    List<StaffImportRowError> warnings) {
        return new StaffImportCheckedRow(row.rowNumber(), row.email(), row.fullName(), row.roles(),
                row.departmentCode(), row.phone(), row.displayTitle(), errors.isEmpty(), List.copyOf(errors),
                List.copyOf(warnings));
    }
}
