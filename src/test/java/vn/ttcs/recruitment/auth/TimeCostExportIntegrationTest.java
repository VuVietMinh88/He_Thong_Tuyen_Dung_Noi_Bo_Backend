package vn.ttcs.recruitment.auth;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Task 576 (story S8-08): the time and cost report as a real XLSX, same numbers, same scope, safe cells. */
class TimeCostExportIntegrationTest extends ReportingTestSupport {
    static final String QUERY = "?from=2026-09-01&to=2026-09-30";
    static final String HOSTILE = "=HYPERLINK(\"http://evil.example\",\"Bấm\")";
    private final HttpClient client = HttpClient.newHttpClient();

    void arrange() {
        jdbc.update("UPDATE departments SET name = ? WHERE id = ?", HOSTILE, department);
        jdbc.update("UPDATE positions SET name = '+Lập trình viên' WHERE id = ?", position);
        Instant approved = Instant.parse("2026-09-01T03:00:00Z");
        workflowEvent(requisition, "APPROVED", "PENDING_APPROVAL", "APPROVED", approved);
        workflowEvent(otherRequisition, "APPROVED", "PENDING_APPROVAL", "APPROVED", approved);
        hire(requisition, "a@example.test", "2026-09-11");
        hire(requisition, "b@example.test", "2026-09-21");
        hire(otherRequisition, "c@example.test", "2026-09-06");
        jdbc.update("INSERT INTO recruitment_costs(id,requisition_id,cost_type,amount_vnd,incurred_on,recorded_by,recorded_at)"
                + " VALUES (?,?,'ADVERTISING',4000000,?,?,?)", UUID.randomUUID(), requisition,
                Date.valueOf("2026-09-15"), hr, Timestamp.from(NOW));
    }

    void hire(UUID requisitionId, String email, String date) {
        Instant at = LocalDate.parse(date).atTime(10, 0).atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant();
        UUID application = application(requisitionId, email, at.minusSeconds(86_400L * 20), "NEW", null);
        stageEvent(application, "OFFER", "HIRED", at);
        jdbc.update("UPDATE job_applications SET stage = 'HIRED' WHERE id = ?", application);
    }

    HttpResponse<byte[]> download(String query, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/reports/time-cost/export"
                + query)).GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void theWorkbookHoldsTheSameNumbersAsTheJsonInSafeCells() throws Exception {
        arrange();
        JsonNode report = okJson("/api/v1/reports/time-cost" + QUERY, hrToken);
        var response = download(QUERY, hrToken);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValue(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(response.headers().firstValue("Content-Disposition")).hasValue(
                "attachment; filename=\"bao-cao-thoi-gian-chi-phi_2026-09-01_2026-09-30.xlsx\"");
        assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(v -> assertThat(v).contains("no-store"));

        try (Workbook book = WorkbookFactory.create(new ByteArrayInputStream(response.body()))) {
            assertThat(sheetNames(book)).containsExactly("Bộ lọc", "Tổng hợp", "Theo phòng ban", "Theo chức danh",
                    "Theo yêu cầu");
            Sheet overall = book.getSheet("Tổng hợp");
            assertThat(overall.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Số người nhận việc");
            JsonNode metrics = report.path("overall");
            assertThat(overall.getRow(1).getCell(0).getNumericCellValue()).isEqualTo(metrics.path("hires").asDouble());
            assertThat(overall.getRow(1).getCell(4).getNumericCellValue())
                    .isEqualTo(metrics.path("timeToHire").path("averageDays").asDouble());
            assertThat(overall.getRow(1).getCell(11).getNumericCellValue())
                    .isEqualTo(metrics.path("costs").path("totalVnd").asDouble());
            assertThat(overall.getRow(1).getCell(13).getNumericCellValue())
                    .isEqualTo(metrics.path("costPerHireVnd").asDouble());

            Sheet departments = book.getSheet("Theo phòng ban");
            assertThat(departments.getLastRowNum()).isEqualTo(report.path("byDepartment").size());
            Cell hostileCell = cellWithText(departments, HOSTILE);
            assertThat(hostileCell.getCellType()).isEqualTo(CellType.STRING);
            assertThat(hostileCell.getCellStyle().getQuotePrefixed()).isTrue();
            assertThat(cellWithText(book.getSheet("Theo chức danh"), "+Lập trình viên").getCellStyle().getQuotePrefixed())
                    .isTrue();
            assertThat(book.getSheet("Theo yêu cầu").getLastRowNum()).isEqualTo(report.path("requisitions").size());
            book.forEach(sheet -> sheet.forEach(row -> row.forEach(cell ->
                    assertThat(cell.getCellType()).as(sheet.getSheetName()).isNotEqualTo(CellType.FORMULA))));
            // No personal data: the export holds groups and requisitions, never candidates.
            book.forEach(sheet -> sheet.forEach(row -> row.forEach(cell -> {
                if (cell.getCellType() == CellType.STRING) {
                    assertThat(cell.getStringCellValue()).doesNotContain("@example.test", "Ứng viên ");
                }
            })));
        }
    }

    @Test
    void theExportFollowsTheSameScopeFiltersAndErrorsAsTheReport() throws Exception {
        arrange();
        try (Workbook book = WorkbookFactory.create(new ByteArrayInputStream(download(QUERY, recruiterToken).body()))) {
            assertThat(book.getSheet("Theo yêu cầu").getLastRowNum()).isEqualTo(1);
            assertThat(book.getSheet("Theo yêu cầu").getRow(1).getCell(0).getStringCellValue())
                    .isEqualTo(requisition.toString());
            assertThat(book.getSheet("Tổng hợp").getRow(1).getCell(0).getNumericCellValue()).isEqualTo(2d);
            assertThat(book.getSheet("Bộ lọc").getRow(3).getCell(1).getStringCellValue())
                    .isEqualTo("Các vị trí người xuất được phụ trách");
        }
        try (Workbook book = WorkbookFactory.create(new ByteArrayInputStream(
                download(QUERY + "&departmentId=" + otherDepartment, hrToken).body()))) {
            assertThat(book.getSheet("Tổng hợp").getRow(1).getCell(0).getNumericCellValue()).isEqualTo(1d);
            // One hire and no cost recorded: no cost per hire, an empty cell rather than 0.
            assertThat(book.getSheet("Tổng hợp").getRow(1).getCell(13)).isNull();
        }
        assertThat(download(QUERY, interviewerToken).statusCode()).isEqualTo(403);
        assertThat(download(QUERY, null).statusCode()).isEqualTo(401);
        assertThat(download("?from=2026-09-01", hrToken).statusCode()).isEqualTo(400);
        assertThat(download(QUERY + "&departmentId=" + otherDepartment, recruiterToken).statusCode()).isEqualTo(403);
        try (Workbook empty = WorkbookFactory.create(new ByteArrayInputStream(
                download("?from=2026-01-01&to=2026-01-31", hrToken).body()))) {
            assertThat(empty.getSheet("Tổng hợp").getRow(1).getCell(0).getNumericCellValue()).isZero();
            assertThat(empty.getSheet("Tổng hợp").getRow(1).getCell(4)).isNull();       // no average without a hire
            assertThat(empty.getSheet("Tổng hợp").getRow(1).getCell(13)).isNull();      // no cost per hire
            assertThat(empty.getSheet("Theo yêu cầu").getLastRowNum()).isZero();
        }
    }

    static List<String> sheetNames(Workbook book) {
        List<String> names = new ArrayList<>();
        book.forEach(sheet -> names.add(sheet.getSheetName()));
        return names;
    }

    static Cell cellWithText(Sheet sheet, String text) {
        for (var row : sheet) {
            for (var cell : row) {
                if (cell.getCellType() == CellType.STRING && cell.getStringCellValue().equals(text)) {
                    return cell;
                }
            }
        }
        throw new AssertionError("No cell with " + text + " in " + sheet.getSheetName());
    }
}
