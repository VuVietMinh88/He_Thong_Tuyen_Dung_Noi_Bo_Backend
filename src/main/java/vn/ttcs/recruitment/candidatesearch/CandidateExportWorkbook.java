package vn.ttcs.recruitment.candidatesearch;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.List;

/**
 * Task 423 (story S6-01, "Xuất theo bộ lọc đang áp dụng"): the .xlsx file of an export, one row per application of
 * the search, in the list's order. Every value is written as a plain string cell, never a formula. The columns are the
 * list's own fields; nothing the list does not show (screening notes, cover letters, CVs, tracking data) is read.
 */
@Component
public class CandidateExportWorkbook {
    public static final MediaType CONTENT_TYPE =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    static final String SHEET = "Ứng viên";
    static final List<String> HEADERS = List.of("STT", "Họ tên", "Email", "Số điện thoại", "Vị trí", "Tin tuyển dụng",
            "Giai đoạn", "Nguồn", "Nhãn", "Đánh giá", "Ngày nộp");

    byte[] write(List<CandidateSearchView.Item> rows) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet(SHEET);
            Row header = sheet.createRow(0);
            for (int column = 0; column < HEADERS.size(); column++) {
                header.createCell(column).setCellValue(HEADERS.get(column));
            }
            for (int index = 0; index < rows.size(); index++) {
                CandidateSearchView.Item item = rows.get(index);
                Row row = sheet.createRow(index + 1);
                List<String> values = List.of(String.valueOf(index + 1), item.fullName(), item.email(), item.phone(),
                        item.positionName(), item.postingTitle(), item.stageName(), text(item.sourceName()),
                        String.join(", ", item.tags()), item.rating() == null ? "" : item.rating().toString(),
                        item.receivedAt().toString());
                for (int column = 0; column < values.size(); column++) {
                    row.createCell(column).setCellValue(values.get(column));
                }
            }
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            // The workbook is written to memory, so this only happens if POI itself fails.
            throw new UncheckedIOException(exception);
        }
    }

    static String filename(LocalDate exportDate) {
        return "ung-vien-" + exportDate + ".xlsx";
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
