package vn.ttcs.recruitment.account.importing;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * Builds the .xlsx file that administrators fill in to import staff accounts. The first sheet holds only the
 * header row, so no sample person can be imported by mistake; the second sheet explains every column and lists
 * the valid role codes. Every cell is a plain string cell, never a formula.
 */
@Component
public class StaffImportTemplate {
    public static final String FILE_NAME = "mau-nhap-nhan-su.xlsx";
    public static final MediaType CONTENT_TYPE =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    public static final String DATA_SHEET = "Nhân sự";
    public static final String GUIDE_SHEET = "Hướng dẫn";
    // The guide promises these limits to administrators; the upload step must enforce the same values.
    public static final int MAX_DATA_ROWS = 500;
    // Spring Boot rejects multipart files above 1 MB by default (spring.servlet.multipart.max-file-size), so the
    // upload step must also raise that setting and max-request-size to at least this size.
    public static final int MAX_FILE_SIZE_MB = 2;

    public byte[] write(List<RoleOption> roles) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Styles styles = new Styles(workbook);
            writeDataSheet(workbook.createSheet(DATA_SHEET), styles);
            writeGuideSheet(workbook.createSheet(GUIDE_SHEET), styles, roles);
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            // The workbook is written to memory, so this only happens if POI itself fails.
            throw new UncheckedIOException(exception);
        }
    }

    private void writeDataSheet(Sheet sheet, Styles styles) {
        Row header = sheet.createRow(0);
        for (StaffImportColumn column : StaffImportColumn.values()) {
            int index = column.ordinal();
            Cell cell = header.createCell(index);
            cell.setCellValue(column.header());
            cell.setCellStyle(column.required() ? styles.requiredHeader : styles.optionalHeader);
            // Text format keeps the leading 0 of phone numbers and stops Excel from turning codes into numbers.
            sheet.setDefaultColumnStyle(index, styles.text);
            int characters = Math.max(column.header().length(), column.example().length()) + 6;
            sheet.setColumnWidth(index, characters * 256);
        }
        // The header row stays visible while scrolling through hundreds of people.
        sheet.createFreezePane(0, 1);
    }

    private void writeGuideSheet(Sheet sheet, Styles styles, List<RoleOption> roles) {
        int row = 0;
        addRow(sheet, row++, styles.title, "Hướng dẫn nhập danh sách nhân sự");
        row++;
        addRow(sheet, row++, styles.bold, "Quy định chung");
        for (String rule : generalRules()) {
            addRow(sheet, row++, null, rule);
        }
        row++;
        addRow(sheet, row++, styles.bold, "Các cột của sheet \"" + DATA_SHEET + "\"");
        addRow(sheet, row++, styles.tableHeader, "Cột", "Tiêu đề", "Mã cột", "Bắt buộc", "Quy tắc", "Ví dụ");
        for (StaffImportColumn column : StaffImportColumn.values()) {
            addRow(sheet, row++, styles.wrapped, CellReference.convertNumToColString(column.ordinal()),
                    column.header(), column.key(), column.required() ? "Có" : "Không", column.rule(), column.example());
        }
        row++;
        addRow(sheet, row++, styles.bold, "Mã vai trò hợp lệ");
        addRow(sheet, row++, styles.tableHeader, "Mã vai trò", "Tên vai trò");
        for (RoleOption role : roles) {
            addRow(sheet, row++, null, role.code(), role.name());
        }
        int[] widths = {16, 22, 16, 10, 70, 28};
        for (int index = 0; index < widths.length; index++) {
            sheet.setColumnWidth(index, widths[index] * 256);
        }
    }

    private static List<String> generalRules() {
        return List.of(
                "1. Chỉ nhập dữ liệu ở sheet đầu tiên \"" + DATA_SHEET + "\"; hệ thống chỉ đọc sheet đầu tiên.",
                "2. Giữ nguyên dòng tiêu đề ở dòng 1: không đổi tên, không đổi thứ tự, không thêm hoặc xóa cột.",
                "3. Từ dòng 2, mỗi dòng là một nhân sự; tối đa " + MAX_DATA_ROWS + " nhân sự trong một tệp.",
                "4. Cột có tiêu đề nền cam là bắt buộc; cột nền xám có thể để trống.",
                "5. Chỉ nhập giá trị, không dùng công thức; lưu tệp dạng .xlsx, dung lượng tối đa "
                        + MAX_FILE_SIZE_MB + " MB.",
                "6. Mỗi tài khoản được tạo ở trạng thái chờ kích hoạt và nhận email mời kích hoạt, "
                        + "giống khi tạo từng tài khoản.");
    }

    private static void addRow(Sheet sheet, int index, CellStyle style, String... values) {
        Row row = sheet.createRow(index);
        for (int column = 0; column < values.length; column++) {
            Cell cell = row.createCell(column);
            cell.setCellValue(values[column]);
            if (style != null) {
                cell.setCellStyle(style);
            }
        }
    }

    /** A role code the import accepts, with its Vietnamese name from the role catalog. */
    public record RoleOption(String code, String name) { }

    private static final class Styles {
        private final CellStyle title;
        private final CellStyle bold;
        private final CellStyle requiredHeader;
        private final CellStyle optionalHeader;
        private final CellStyle tableHeader;
        private final CellStyle wrapped;
        private final CellStyle text;

        private Styles(Workbook workbook) {
            Font boldFont = workbook.createFont();
            boldFont.setBold(true);
            Font titleFont = workbook.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);

            title = workbook.createCellStyle();
            title.setFont(titleFont);
            bold = workbook.createCellStyle();
            bold.setFont(boldFont);
            requiredHeader = header(workbook, boldFont, IndexedColors.LIGHT_ORANGE);
            optionalHeader = header(workbook, boldFont, IndexedColors.GREY_25_PERCENT);
            tableHeader = header(workbook, boldFont, IndexedColors.GREY_25_PERCENT);
            wrapped = workbook.createCellStyle();
            wrapped.setWrapText(true);
            wrapped.setVerticalAlignment(VerticalAlignment.TOP);
            text = workbook.createCellStyle();
            text.setDataFormat(workbook.createDataFormat().getFormat("@"));
        }

        private static CellStyle header(Workbook workbook, Font font, IndexedColors color) {
            CellStyle style = workbook.createCellStyle();
            style.setFont(font);
            style.setFillForegroundColor(color.getIndex());
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            style.setBorderBottom(BorderStyle.THIN);
            style.setVerticalAlignment(VerticalAlignment.TOP);
            return style;
        }
    }
}
