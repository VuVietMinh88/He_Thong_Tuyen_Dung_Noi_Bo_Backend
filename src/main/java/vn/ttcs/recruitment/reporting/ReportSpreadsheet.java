package vn.ttcs.recruitment.reporting;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import vn.ttcs.recruitment.common.ApiException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Tasks 576 and 582: a real XLSX workbook built with Apache POI from the same report object the JSON endpoint returns.
 * Safe cell types: numbers are numeric cells, dates are text in ISO form, everything else is a string cell; no cell is
 * ever a formula. A text that a spreadsheet could read as a formula when it is copied or saved as CSV (it starts with
 * =, +, -, @, a tab or a carriage return) is also marked quote-prefixed, so Excel shows and keeps it as literal text.
 * A sheet holds at most MAX_ROWS data rows; a larger report is refused (400 REPORT_TOO_LARGE) instead of cut silently.
 */
final class ReportSpreadsheet implements AutoCloseable {
    static final String MEDIA_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    static final int MAX_ROWS = 5_000;

    private final XSSFWorkbook book = new XSSFWorkbook();
    private final CellStyle header;
    private final CellStyle text;
    private final CellStyle literal;
    private final CellStyle integer;
    private final CellStyle money;
    private final CellStyle decimal;
    private final CellStyle percent;

    ReportSpreadsheet() {
        Font bold = book.createFont();
        bold.setBold(true);
        header = book.createCellStyle();
        header.setFont(bold);
        text = book.createCellStyle();
        literal = book.createCellStyle();
        literal.setQuotePrefixed(true);
        var format = book.createDataFormat();
        integer = book.createCellStyle();
        integer.setDataFormat(format.getFormat("0"));
        money = book.createCellStyle();
        money.setDataFormat(format.getFormat("#,##0"));
        decimal = book.createCellStyle();
        decimal.setDataFormat(format.getFormat("0.0"));
        percent = book.createCellStyle();
        percent.setDataFormat(format.getFormat("0.00%"));
    }

    /** A sheet with a bold header row (frozen), sized for at most MAX_ROWS rows. */
    Sheet sheet(String name, List<String> headers, int rows) {
        if (rows > MAX_ROWS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REPORT_TOO_LARGE",
                    "Báo cáo vượt quá " + MAX_ROWS + " dòng. Hãy thu hẹp bộ lọc hoặc khoảng thời gian.");
        }
        Sheet sheet = book.createSheet(name);
        Row row = sheet.createRow(0);
        for (int column = 0; column < headers.size(); column++) {
            Cell cell = row.createCell(column);
            cell.setCellValue(headers.get(column));
            cell.setCellStyle(header);
            sheet.setColumnWidth(column, Math.min(60, Math.max(12, headers.get(column).length() + 4)) * 256);
        }
        sheet.createFreezePane(0, 1);
        return sheet;
    }

    /** Appends one row of values: String, LocalDate, Integer/Long (count), Money, Days, Rate, or null (empty cell). */
    void row(Sheet sheet, Object... values) {
        Row row = sheet.createRow(sheet.getLastRowNum() + 1);
        for (int column = 0; column < values.length; column++) {
            Object value = values[column];
            if (value == null) {
                continue;
            }
            Cell cell = row.createCell(column);
            switch (value) {
                case String string -> {
                    cell.setCellValue(string);
                    cell.setCellStyle(formulaLike(string) ? literal : text);
                }
                case LocalDate date -> cell.setCellValue(date.toString());
                case Money amount -> {
                    cell.setCellValue(amount.vnd());
                    cell.setCellStyle(money);
                }
                case Days days -> {
                    cell.setCellValue(days.value().doubleValue());
                    cell.setCellStyle(decimal);
                }
                case Rate rate -> {
                    cell.setCellValue(rate.value().doubleValue());
                    cell.setCellStyle(percent);
                }
                case Long number -> {
                    cell.setCellValue(number);
                    cell.setCellStyle(integer);
                }
                case Integer number -> {
                    cell.setCellValue(number);
                    cell.setCellStyle(integer);
                }
                default -> throw new IllegalArgumentException("Unsupported cell value " + value.getClass());
            }
        }
    }

    byte[] bytes() {
        try (var out = new ByteArrayOutputStream()) {
            book.write(out);
            return out.toByteArray();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @Override
    public void close() {
        try {
            book.close();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static boolean formulaLike(String value) {
        if (value.isEmpty()) {
            return false;
        }
        char first = value.charAt(0);
        return first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r';
    }

    /** Whole VND, shown with thousands separators. */
    record Money(long vnd) { }

    /** A number of days with one decimal. */
    record Days(BigDecimal value) { }

    /** A rate between 0 and 1, shown as a percentage. */
    record Rate(BigDecimal value) { }

    static Money money(Long vnd) {
        return vnd == null ? null : new Money(vnd);
    }

    static Days days(BigDecimal value) {
        return value == null ? null : new Days(value);
    }

    static Rate rate(BigDecimal value) {
        return value == null ? null : new Rate(value);
    }
}
