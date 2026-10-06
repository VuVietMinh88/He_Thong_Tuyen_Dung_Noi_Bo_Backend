package vn.ttcs.recruitment.account.importing;

import java.util.List;

/** What the administrator sees before importing: every filled-in row of the first sheet, in file order. */
public record StaffImportPreview(int totalRows, List<StaffImportRow> rows) {

    public static StaffImportPreview of(List<StaffImportRow> rows) {
        return new StaffImportPreview(rows.size(), List.copyOf(rows));
    }
}
