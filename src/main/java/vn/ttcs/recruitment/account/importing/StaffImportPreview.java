package vn.ttcs.recruitment.account.importing;

import java.util.List;

/**
 * What the administrator sees before importing: every filled-in row of the first sheet, in file order, each
 * marked valid or invalid with its errors. {@code totalRows = validRows + invalidRows}.
 */
public record StaffImportPreview(int totalRows, int validRows, int invalidRows, List<StaffImportCheckedRow> rows) {

    public static StaffImportPreview of(List<StaffImportCheckedRow> rows) {
        int valid = (int) rows.stream().filter(StaffImportCheckedRow::valid).count();
        return new StaffImportPreview(rows.size(), valid, rows.size() - valid, List.copyOf(rows));
    }
}
