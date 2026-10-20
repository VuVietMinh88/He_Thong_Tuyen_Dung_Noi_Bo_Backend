package vn.ttcs.recruitment.reporting;

import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import vn.ttcs.recruitment.common.ApiException;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tasks 576 and 582: safe cell types, neutralized formula-like text and bounded sheets. */
class ReportSpreadsheetTest {

    @Test
    void textThatLooksLikeAFormulaStaysALiteralQuotePrefixedString() throws Exception {
        byte[] bytes;
        try (var spreadsheet = new ReportSpreadsheet()) {
            var sheet = spreadsheet.sheet("Thử", List.of("A", "B", "C", "D", "E", "F", "G", "H"), 2);
            spreadsheet.row(sheet, "=HYPERLINK(\"http://x\",\"y\")", "+1", "-2", "@SUM(A1)", "Bình thường",
                    ReportSpreadsheet.money(1_234_567L), ReportSpreadsheet.days(new BigDecimal("12.5")),
                    ReportSpreadsheet.rate(new BigDecimal("0.4286")));
            spreadsheet.row(sheet, LocalDate.of(2026, 9, 1), 42L, 7, null, ReportSpreadsheet.money(null));
            bytes = spreadsheet.bytes();
        }
        try (var book = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            var sheet = book.getSheet("Thử");
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("A");
            var row = sheet.getRow(1);
            for (int column = 0; column < 4; column++) {
                assertThat(row.getCell(column).getCellType()).isEqualTo(CellType.STRING);
                assertThat(row.getCell(column).getCellStyle().getQuotePrefixed()).isTrue();
            }
            assertThat(row.getCell(0).getStringCellValue()).isEqualTo("=HYPERLINK(\"http://x\",\"y\")");
            assertThat(row.getCell(4).getCellStyle().getQuotePrefixed()).isFalse();
            assertThat(row.getCell(5).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(row.getCell(5).getNumericCellValue()).isEqualTo(1_234_567d);
            assertThat(row.getCell(6).getNumericCellValue()).isEqualTo(12.5d);
            assertThat(row.getCell(7).getNumericCellValue()).isEqualTo(0.4286d);
            var second = sheet.getRow(2);
            assertThat(second.getCell(0).getStringCellValue()).isEqualTo("2026-09-01");
            assertThat(second.getCell(1).getNumericCellValue()).isEqualTo(42d);
            assertThat(second.getCell(2).getNumericCellValue()).isEqualTo(7d);
            assertThat(second.getCell(3)).isNull();
            assertThat(second.getCell(4)).isNull();
            sheet.forEach(r -> r.forEach(cell -> assertThat(cell.getCellType()).isNotEqualTo(CellType.FORMULA)));
        }
        assertThat(ReportSpreadsheet.formulaLike("\tx")).isTrue();
        assertThat(ReportSpreadsheet.formulaLike("\rx")).isTrue();
        assertThat(ReportSpreadsheet.formulaLike("")).isFalse();
        assertThat(ReportSpreadsheet.formulaLike("a=b")).isFalse();
    }

    @Test
    void aSheetLargerThanTheLimitIsRefusedNotCut() {
        try (var spreadsheet = new ReportSpreadsheet()) {
            assertThatThrownBy(() -> spreadsheet.sheet("Lớn", List.of("A"), ReportSpreadsheet.MAX_ROWS + 1))
                    .isInstanceOfSatisfying(ApiException.class,
                            exception -> assertThat(exception.getCode()).isEqualTo("REPORT_TOO_LARGE"));
            assertThat(spreadsheet.sheet("Vừa", List.of("A"), ReportSpreadsheet.MAX_ROWS)).isNotNull();
        }
    }
}
