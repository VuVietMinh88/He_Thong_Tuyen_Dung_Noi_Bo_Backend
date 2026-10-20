package vn.ttcs.recruitment.candidatesearch;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import vn.ttcs.recruitment.common.BusinessCalendar;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Tasks 423-424 (story S6-01, "Xuất theo bộ lọc đang áp dụng; tệp không chứa ghi chú nội bộ nhạy cảm; tên tệp chứa mã
 * vị trí và ngày xuất"): the .xlsx file of an export, one row per application of the search, in the list's order.
 * <p>
 * Task 424: the columns are a fixed allow-list (Column) of the list's own fields; nothing the list does not show
 * (screening notes, cover letters, CVs, tracking codes, ids) is ever read or written. Every text is a string cell, never
 * a formula; a text that a spreadsheet would read as a formula when edited (starting with =, +, -, @, a tab or a line
 * break, also in their full-width forms) gets the quote-prefix style, so it stays text, and control characters are
 * removed. Numbers (row number, rating) are numeric cells, the submission time a date cell in the business zone. A
 * second sheet records when the file was made and the filter it applied.
 * <p>
 * The workbook is streamed (SXSSF): at most WINDOW rows are kept in memory, older ones go to compressed temporary files
 * that dispose() deletes in finally, so a 10 000-row export does not hold the whole sheet as objects.
 */
@Component
public class CandidateExportWorkbook {
    public static final MediaType CONTENT_TYPE =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    static final String SHEET = "Ứng viên";
    static final String INFO_SHEET = "Thông tin xuất";
    // Excel's limit for one cell; no exported field comes near it, but a longer text would make the file unreadable.
    private static final int MAX_CELL_LENGTH = 32_767;
    private static final int WINDOW = 100;
    private static final DateTimeFormatter EXPORTED_AT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.ROOT);
    private final BusinessCalendar calendar;

    public CandidateExportWorkbook(BusinessCalendar calendar) {
        this.calendar = calendar;
    }

    /** The exported columns, in order. A new column is a deliberate change here, never a side effect of the query. */
    enum Column {
        NUMBER("STT", 6),
        POSITION_CODE("Mã vị trí", 14),
        POSITION("Vị trí", 28),
        POSTING("Tin tuyển dụng", 30),
        FULL_NAME("Họ tên", 26),
        EMAIL("Email", 30),
        PHONE("Số điện thoại", 16),
        STAGE("Giai đoạn", 18),
        SOURCE("Nguồn", 18),
        TAGS("Nhãn", 28),
        RATING("Đánh giá", 10),
        RECEIVED_AT("Ngày nộp", 18);

        final String header;
        final int width;

        Column(String header, int width) {
            this.header = header;
            this.width = width;
        }
    }

    /** One line of the information sheet: a label and its value, both plain text. */
    record InfoLine(String label, String value) { }

    byte[] write(List<CandidateSearchView.Item> rows, Instant exportedAt, List<InfoLine> filter) {
        SXSSFWorkbook workbook = new SXSSFWorkbook(WINDOW);
        workbook.setCompressTempFiles(true);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Styles styles = new Styles(workbook);
            writeRows(workbook.createSheet(SHEET), styles, rows);
            writeInfo(workbook.createSheet(INFO_SHEET), styles, rows.size(), exportedAt, filter);
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            // The file is written to memory and POI's own temporary files, so this only happens if POI itself fails.
            throw new UncheckedIOException(exception);
        } finally {
            workbook.dispose();
            try {
                workbook.close();
            } catch (IOException exception) {
                // Closing a workbook written to memory releases nothing that can fail in a way the caller can handle.
            }
        }
    }

    private void writeRows(Sheet sheet, Styles styles, List<CandidateSearchView.Item> rows) {
        Row header = sheet.createRow(0);
        for (Column column : Column.values()) {
            Cell cell = header.createCell(column.ordinal());
            cell.setCellValue(column.header);
            cell.setCellStyle(styles.header);
            sheet.setColumnWidth(column.ordinal(), column.width * 256);
        }
        for (int index = 0; index < rows.size(); index++) {
            CandidateSearchView.Item item = rows.get(index);
            Row row = sheet.createRow(index + 1);
            for (Column column : Column.values()) {
                Cell cell = row.createCell(column.ordinal());
                switch (column) {
                    case NUMBER -> cell.setCellValue(index + 1);
                    case POSITION_CODE -> text(cell, styles, item.positionCode());
                    case POSITION -> text(cell, styles, item.positionName());
                    case POSTING -> text(cell, styles, item.postingTitle());
                    case FULL_NAME -> text(cell, styles, item.fullName());
                    case EMAIL -> text(cell, styles, item.email());
                    case PHONE -> text(cell, styles, item.phone());
                    case STAGE -> text(cell, styles, item.stageName());
                    case SOURCE -> text(cell, styles, item.sourceName());
                    case TAGS -> text(cell, styles, String.join(", ", item.tags()));
                    case RATING -> {
                        if (item.rating() != null) cell.setCellValue(item.rating());
                    }
                    case RECEIVED_AT -> {
                        cell.setCellValue(LocalDateTime.ofInstant(item.receivedAt(), calendar.zone()));
                        cell.setCellStyle(styles.dateTime);
                    }
                }
            }
        }
        // The header stays visible while scrolling, and every column can be sorted or filtered in the spreadsheet.
        sheet.createFreezePane(0, 1);
        sheet.setAutoFilter(new CellRangeAddress(0, Math.max(rows.size(), 1), 0, Column.values().length - 1));
    }

    private void writeInfo(Sheet sheet, Styles styles, int count, Instant exportedAt, List<InfoLine> filter) {
        int index = 0;
        line(sheet, styles, index++, "Thời điểm xuất", EXPORTED_AT.format(LocalDateTime.ofInstant(exportedAt,
                calendar.zone())) + " (" + calendar.zone().getId() + ")");
        line(sheet, styles, index++, "Số hồ sơ", Integer.toString(count));
        for (InfoLine info : filter) {
            line(sheet, styles, index++, info.label(), info.value());
        }
        sheet.setColumnWidth(0, 28 * 256);
        sheet.setColumnWidth(1, 70 * 256);
    }

    private static void line(Sheet sheet, Styles styles, int index, String label, String value) {
        Row row = sheet.createRow(index);
        Cell name = row.createCell(0);
        name.setCellValue(label);
        name.setCellStyle(styles.header);
        text(row.createCell(1), styles, value);
    }

    // A string cell; a value a spreadsheet could take for a formula is quote-prefixed, so it stays text.
    private static void text(Cell cell, Styles styles, String value) {
        String safe = safeText(value);
        cell.setCellValue(safe);
        if (formulaLike(safe)) cell.setCellStyle(styles.quoted);
    }

    // Control characters (other than tab and line breaks, which only matter as a first character) are removed: they
    // are not allowed in the file's XML.
    static String safeText(String value) {
        if (value == null) return "";
        StringBuilder safe = new StringBuilder(value.length());
        value.codePoints().filter(point -> !Character.isISOControl(point) || point == '\t' || point == '\n'
                || point == '\r').forEach(safe::appendCodePoint);
        return safe.length() > MAX_CELL_LENGTH ? safe.substring(0, MAX_CELL_LENGTH) : safe.toString();
    }

    static boolean formulaLike(String value) {
        if (value.isEmpty()) return false;
        return switch (value.charAt(0)) {
            case '=', '+', '-', '@', '\t', '\r', '\n' -> true;
            // The full-width forms of = + - @, which some spreadsheets also accept.
            case (char) 0xFF1D, (char) 0xFF0B, (char) 0xFF0D, (char) 0xFF20 -> true;
            default -> false;
        };
    }

    // ung-vien_<position code>_<export date>.xlsx; TAT-CA when the filter names no position. The code keeps only
    // letters, digits, dots, dashes and underscores (others become "-"), so the name is safe on every system.
    static String filename(String positionCode, LocalDate exportDate) {
        String code = positionCode == null ? "TAT-CA" : safeCode(positionCode);
        return "ung-vien_" + code + "_" + exportDate + ".xlsx";
    }

    static String safeCode(String positionCode) {
        String code = positionCode.strip().replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^[-.]+|[-.]+$", "");
        if (code.length() > 50) code = code.substring(0, 50);
        return code.isEmpty() ? "VI-TRI" : code;
    }

    private static final class Styles {
        final CellStyle header;
        final CellStyle quoted;
        final CellStyle dateTime;

        Styles(Workbook workbook) {
            Font bold = workbook.createFont();
            bold.setBold(true);
            header = workbook.createCellStyle();
            header.setFont(bold);
            quoted = workbook.createCellStyle();
            quoted.setQuotePrefixed(true);
            dateTime = workbook.createCellStyle();
            dateTime.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("dd/mm/yyyy hh:mm"));
        }
    }
}
