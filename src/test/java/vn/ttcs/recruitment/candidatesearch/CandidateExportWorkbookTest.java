package vn.ttcs.recruitment.candidatesearch;

import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import vn.ttcs.recruitment.application.ApplicationStage;
import vn.ttcs.recruitment.common.BusinessCalendar;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CandidateExportWorkbookTest {
    private final CandidateExportWorkbook workbook = new CandidateExportWorkbook(new BusinessCalendar(
            Clock.fixed(Instant.parse("2026-10-15T03:00:00Z"), ZoneOffset.UTC), ZoneId.of("Asia/Ho_Chi_Minh")));

    private static CandidateSearchView.Item item(String name, String tag, Integer rating, Instant receivedAt) {
        return new CandidateSearchView.Item(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "DEV-01", "Lập trình viên", "@Java Developer", name, "a@example.test", "0912345678",
                ApplicationStage.SCREENING, "Sàng lọc", "-LinkedIn", List.of(tag, "Java"), rating, receivedAt,
                List.of("NAME"));
    }

    @Test
    void columnsAreAFixedAllowListOfTheListsOwnFields() {
        assertThat(Arrays.stream(CandidateExportWorkbook.Column.values()).map(column -> column.header))
                .containsExactly("STT", "Mã vị trí", "Vị trí", "Tin tuyển dụng", "Họ tên", "Email", "Số điện thoại",
                        "Giai đoạn", "Nguồn", "Nhãn", "Đánh giá", "Ngày nộp");
        // No column names anything the list does not show.
        assertThat(Arrays.stream(CandidateExportWorkbook.Column.values()).map(Enum::name))
                .noneMatch(name -> name.contains("NOTE") || name.contains("COVER") || name.contains("CV")
                        || name.contains("TRACKING") || name.contains("ID"));
    }

    @Test
    void formulaLikeTextsStayTextAndNumbersAndDatesAreTyped() throws Exception {
        byte[] content = workbook.write(List.of(
                item("=HYPERLINK(\"http://evil.example\",\"x\")", "+cmd|' /C calc'!A0", 4,
                        Instant.parse("2026-10-09T17:30:00Z")),
                item("Nguyễn " + (char) 0x0007 + "Văn A", (char) 0xFF1D + "SUM(1)", null,
                        Instant.parse("2026-10-15T03:00:00Z"))),
                Instant.parse("2026-10-14T18:00:00Z"),
                List.of(new CandidateExportWorkbook.InfoLine("Từ khóa (tên, email, điện thoại)", "=1+1")));
        try (var file = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            Sheet sheet = file.getSheet(CandidateExportWorkbook.SHEET);
            Row first = sheet.getRow(1);
            var name = first.getCell(CandidateExportWorkbook.Column.FULL_NAME.ordinal());
            assertThat(name.getCellType()).isEqualTo(CellType.STRING);
            assertThat(name.getStringCellValue()).startsWith("=HYPERLINK(");
            assertThat(name.getCellStyle().getQuotePrefixed()).isTrue();
            assertThat(first.getCell(CandidateExportWorkbook.Column.TAGS.ordinal()).getCellStyle().getQuotePrefixed()).isTrue();
            assertThat(first.getCell(CandidateExportWorkbook.Column.POSTING.ordinal()).getCellStyle().getQuotePrefixed()).isTrue();
            assertThat(first.getCell(CandidateExportWorkbook.Column.SOURCE.ordinal()).getCellStyle().getQuotePrefixed()).isTrue();
            assertThat(first.getCell(CandidateExportWorkbook.Column.EMAIL.ordinal()).getCellStyle().getQuotePrefixed()).isFalse();
            var number = first.getCell(CandidateExportWorkbook.Column.NUMBER.ordinal());
            assertThat(number.getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(number.getNumericCellValue()).isEqualTo(1);
            assertThat(first.getCell(CandidateExportWorkbook.Column.RATING.ordinal()).getNumericCellValue()).isEqualTo(4);
            var received = first.getCell(CandidateExportWorkbook.Column.RECEIVED_AT.ordinal());
            assertThat(DateUtil.isCellDateFormatted(received)).isTrue();
            // 17:30 UTC on 9 Oct is 00:30 on 10 Oct in Vietnam.
            assertThat(received.getLocalDateTimeCellValue()).isEqualTo(LocalDateTime.of(2026, 10, 10, 0, 30));
            assertThat(new DataFormatter().formatCellValue(received)).isEqualTo("10/10/2026 00:30");

            Row second = sheet.getRow(2);
            assertThat(second.getCell(CandidateExportWorkbook.Column.FULL_NAME.ordinal()).getStringCellValue())
                    .isEqualTo("Nguyễn Văn A");
            assertThat(second.getCell(CandidateExportWorkbook.Column.TAGS.ordinal()).getCellStyle().getQuotePrefixed()).isTrue();
            assertThat(second.getCell(CandidateExportWorkbook.Column.RATING.ordinal()).getCellType()).isEqualTo(CellType.BLANK);
            for (Row row : sheet) {
                row.forEach(cell -> assertThat(cell.getCellType()).isNotEqualTo(CellType.FORMULA));
            }
            assertThat(sheet.getPaneInformation().isFreezePane()).isTrue();

            Sheet info = file.getSheet(CandidateExportWorkbook.INFO_SHEET);
            assertThat(info.getRow(0).getCell(1).getStringCellValue()).isEqualTo("15/10/2026 01:00 (Asia/Ho_Chi_Minh)");
            assertThat(info.getRow(1).getCell(1).getStringCellValue()).isEqualTo("2");
            assertThat(info.getRow(2).getCell(1).getCellStyle().getQuotePrefixed()).isTrue();
        }
    }

    @Test
    void theFileIsNamedAfterThePositionCodeAndTheExportDate() {
        assertThat(CandidateExportWorkbook.filename("DEV-JAVA", LocalDate.of(2026, 10, 15)))
                .isEqualTo("ung-vien_DEV-JAVA_2026-10-15.xlsx");
        assertThat(CandidateExportWorkbook.filename(null, LocalDate.of(2026, 10, 15)))
                .isEqualTo("ung-vien_TAT-CA_2026-10-15.xlsx");
        assertThat(CandidateExportWorkbook.safeCode(" ../DEV/JAVA 01\"; x ")).isEqualTo("DEV-JAVA-01-x");
        assertThat(CandidateExportWorkbook.safeCode("Kế toán")).isEqualTo("K-to-n");
        assertThat(CandidateExportWorkbook.safeCode("///")).isEqualTo("VI-TRI");
        assertThat(CandidateExportWorkbook.safeCode("A".repeat(80))).hasSize(50);
    }

    @Test
    void onlyLeadingFormulaCharactersCount() {
        for (String risky : List.of("=1", "+1", "-1", "@A", "\t=1", "\r=1", "\n=1", (char) 0xFF0B + "1",
                (char) 0xFF0D + "1", (char) 0xFF20 + "A")) {
            assertThat(CandidateExportWorkbook.formulaLike(risky)).as(risky).isTrue();
        }
        for (String plain : List.of("", "a=1", "Nguyễn", "0912345678", "a@example.test")) {
            assertThat(CandidateExportWorkbook.formulaLike(plain)).as(plain).isFalse();
        }
        assertThat(CandidateExportWorkbook.safeText(null)).isEmpty();
        assertThat(CandidateExportWorkbook.safeText("a" + (char) 0 + "b" + (char) 0x1F + "c\nd")).isEqualTo("abc\nd");
    }
}
