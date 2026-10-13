package vn.ttcs.recruitment.account.importing;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import vn.ttcs.recruitment.account.ProfileValidation;
import vn.ttcs.recruitment.common.ApiException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipInputStream;

/**
 * Reads the rows of an uploaded staff import file. Only the first sheet is read, its row 1 must be exactly the
 * template header, and formulas are refused instead of evaluated. Reading never writes anything anywhere.
 */
@Component
public class StaffImportReader {
    // A filled-in template unzips to well under 1 MB. Anything far bigger is refused before POI loads it into
    // memory, so a small upload cannot expand into hundreds of megabytes (a "zip bomb"). POI's own
    // ZipSecureFile checks (inflate ratio, entry count) still run when the workbook is opened.
    private static final int MAX_UNZIPPED_SIZE_MB = 10;
    private static final long MAX_UNZIPPED_BYTES = MAX_UNZIPPED_SIZE_MB * 1024L * 1024L;

    public List<StaffImportRow> read(byte[] content) {
        requireSmallUnzippedSize(content);
        try (XSSFWorkbook workbook = open(content)) {
            if (workbook.getNumberOfSheets() == 0) {
                throw notAnXlsxFile();
            }
            Sheet sheet = workbook.getSheetAt(0);
            // DataFormatter is not thread-safe, so every upload gets its own.
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            requireTemplateHeader(sheet.getRow(0), formatter);

            List<StaffImportRow> rows = new ArrayList<>();
            for (Row row : sheet) {
                if (row.getRowNum() == 0) {
                    continue;
                }
                StaffImportRow staff = readRow(row, formatter);
                if (staff == null) {
                    continue;
                }
                if (rows.size() == StaffImportTemplate.MAX_DATA_ROWS) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "IMPORT_TOO_MANY_ROWS",
                            "Tệp có hơn " + StaffImportTemplate.MAX_DATA_ROWS + " dòng nhân sự. "
                                    + "Hãy chia thành nhiều tệp, mỗi tệp tối đa "
                                    + StaffImportTemplate.MAX_DATA_ROWS + " dòng.");
                }
                rows.add(staff);
            }
            if (rows.isEmpty()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "IMPORT_FILE_EMPTY",
                        "Tệp chưa có dòng nhân sự nào. Hãy nhập dữ liệu từ dòng 2 của sheet đầu tiên.");
            }
            return rows;
        } catch (IOException exception) {
            // Only closing the in-memory workbook can fail here.
            throw new UncheckedIOException(exception);
        }
    }

    // A .xlsx file is a zip archive. Unzip it once only to count the bytes, keeping nothing.
    private static void requireSmallUnzippedSize(byte[] content) {
        long total = 0;
        byte[] buffer = new byte[8192];
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            while (zip.getNextEntry() != null) {
                for (int read = zip.read(buffer); read != -1; read = zip.read(buffer)) {
                    total += read;
                    if (total > MAX_UNZIPPED_BYTES) {
                        throw new ApiException(HttpStatus.BAD_REQUEST, "IMPORT_FILE_INVALID",
                                "Nội dung tệp sau khi giải nén vượt quá " + MAX_UNZIPPED_SIZE_MB
                                        + " MB, không giống tệp danh sách nhân sự. "
                                        + "Hãy điền vào tệp mẫu và chỉ giữ dữ liệu cần nhập.");
                    }
                }
            }
        } catch (IOException | IllegalArgumentException exception) {
            // Not a zip archive, or a damaged one.
            throw notAnXlsxFile();
        }
    }

    private static XSSFWorkbook open(byte[] content) {
        try {
            return new XSSFWorkbook(new ByteArrayInputStream(content));
        } catch (IOException | RuntimeException exception) {
            // POI reports damaged, encrypted, old .xls and non-Excel files with many different exception types.
            throw notAnXlsxFile();
        }
    }

    // Row 1 must be the six template headers in template order, with nothing after them. Spaces around a
    // header and the way Vietnamese accents are encoded do not matter; any other difference does.
    private static void requireTemplateHeader(Row header, DataFormatter formatter) {
        StaffImportColumn[] columns = StaffImportColumn.values();
        for (StaffImportColumn column : columns) {
            String actual = header == null ? null : text(header.getCell(column.ordinal()), formatter);
            if (actual == null || !column.header().equals(Normalizer.normalize(actual, Normalizer.Form.NFC))) {
                throw headerInvalid("ô " + CellReference.convertNumToColString(column.ordinal()) + "1 phải là \""
                        + column.header() + "\"");
            }
        }
        for (int index = columns.length; index < header.getLastCellNum(); index++) {
            if (text(header.getCell(index), formatter) != null) {
                throw headerInvalid("ô " + CellReference.convertNumToColString(index) + "1 phải để trống, "
                        + "không thêm cột ngoài " + columns.length + " cột của tệp mẫu");
            }
        }
    }

    // Returns null for a row whose six template cells are all empty, so blank rows between people are skipped.
    // Cells to the right of the template columns are not read.
    private static StaffImportRow readRow(Row row, DataFormatter formatter) {
        Map<StaffImportColumn, String> cells = new EnumMap<>(StaffImportColumn.class);
        for (StaffImportColumn column : StaffImportColumn.values()) {
            String value = text(row.getCell(column.ordinal()), formatter);
            if (value != null) {
                cells.put(column, value);
            }
        }
        if (cells.isEmpty()) {
            return null;
        }
        // Same normalization as CreateAccountRequest and the profile phone rule. StaffImportValidator checks the
        // values afterwards and reports them per row, so nothing is rejected here.
        String email = cells.get(StaffImportColumn.EMAIL);
        return new StaffImportRow(row.getRowNum() + 1,
                email == null ? null : email.toLowerCase(Locale.ROOT),
                cells.get(StaffImportColumn.FULL_NAME),
                roleCodes(cells.get(StaffImportColumn.ROLES)),
                cells.get(StaffImportColumn.DEPARTMENT_CODE),
                ProfileValidation.normalizePhone(cells.get(StaffImportColumn.PHONE)),
                cells.get(StaffImportColumn.DISPLAY_TITLE));
    }

    // "recruiter, INTERVIEWER," becomes [RECRUITER, INTERVIEWER]: split on commas, trim, upper-case, no repeats.
    private static List<String> roleCodes(String value) {
        if (value == null) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(code -> !code.isEmpty())
                .map(code -> code.toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    // The cell as Excel shows it, trimmed; null when empty. A formula is refused, never evaluated.
    private static String text(Cell cell, DataFormatter formatter) {
        if (cell == null) {
            return null;
        }
        if (cell.getCellType() == CellType.FORMULA) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IMPORT_FORMULA_NOT_ALLOWED",
                    "Ô " + cell.getAddress().formatAsString() + " đang dùng công thức. Chỉ nhập giá trị: "
                            + "sao chép rồi dán dạng giá trị (Paste Values) trước khi tải lên.");
        }
        return ProfileValidation.optionalText(formatter.formatCellValue(cell));
    }

    private static ApiException headerInvalid(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, "IMPORT_HEADER_INVALID",
                "Dòng tiêu đề không đúng tệp mẫu: " + detail + ". Hãy tải lại tệp mẫu và giữ nguyên dòng 1.");
    }

    private static ApiException notAnXlsxFile() {
        return new ApiException(HttpStatus.BAD_REQUEST, "IMPORT_FILE_INVALID",
                "Không đọc được tệp. Chỉ nhận tệp Excel .xlsx; tệp .xls, .csv hoặc tệp có mật khẩu không dùng được.");
    }
}
