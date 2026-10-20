package vn.ttcs.recruitment.auth;

import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
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
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.ttcs.recruitment.auth.TimeCostExportIntegrationTest.cellWithText;
import static vn.ttcs.recruitment.auth.TimeCostExportIntegrationTest.sheetNames;

/** Task 582 (story S8-09): the source quality report as a real XLSX matching the JSON, with safe cells. */
class SourceExportIntegrationTest extends ReportingTestSupport {
    static final String QUERY = "?from=2026-09-25&to=2026-10-15";
    static final Instant DAY = Instant.parse("2026-10-01T03:00:00Z");
    private final HttpClient client = HttpClient.newHttpClient();

    void arrange() {
        UUID referral = applications.source("Giới thiệu");
        UUID hostile = applications.source("=1+1");
        for (int index = 0; index < 5; index++) {
            UUID application = application(requisition, "r" + index + "@example.test", DAY, "NEW", referral);
            if (index < 2) {
                path(application, DAY, "SCREENING", "INTERVIEW", "OFFER", "HIRED");
            } else {
                path(application, DAY, "SCREENING", "REJECTED");
            }
        }
        application(otherRequisition, "h@example.test", DAY, "NEW", hostile);
        try {
            referred(requisition, "ref@example.test");                       // through the real referral API
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    HttpResponse<byte[]> download(String query, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/reports/sources/export"
                + query)).GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void theWorkbookMatchesTheJsonAndKeepsTheUnknownSourceAndEmptyRates() throws Exception {
        arrange();
        JsonNode report = okJson("/api/v1/reports/sources" + QUERY, hrToken);
        var response = download(QUERY, hrToken);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValue("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(response.headers().firstValue("Content-Disposition"))
                .hasValue("attachment; filename=\"bao-cao-chat-luong-nguon_2026-09-25_2026-10-15.xlsx\"");
        assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(v -> assertThat(v).contains("no-store"));

        try (Workbook book = WorkbookFactory.create(new ByteArrayInputStream(response.body()))) {
            assertThat(sheetNames(book)).containsExactly("Bộ lọc", "Theo nguồn");
            Sheet sources = book.getSheet("Theo nguồn");
            assertThat(sources.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Hạng chất lượng");
            assertThat(sources.getLastRowNum()).isEqualTo(report.path("sources").size());
            for (int index = 0; index < report.path("sources").size(); index++) {
                JsonNode json = report.path("sources").path(index);
                Row row = sources.getRow(index + 1);
                assertThat(row.getCell(1).getStringCellValue()).isEqualTo(json.path("name").asText());
                assertThat(row.getCell(4).getNumericCellValue()).isEqualTo(json.path("applications").asDouble());
                assertThat(row.getCell(7).getNumericCellValue()).isEqualTo(json.path("hired").asDouble());
                if (json.path("qualityRank").isNull()) {
                    assertThat(row.getCell(0)).isNull();
                } else {
                    assertThat(row.getCell(0).getNumericCellValue()).isEqualTo(json.path("qualityRank").asDouble());
                }
                assertThat(row.getCell(8).getNumericCellValue()).isEqualTo(json.path("hireRate").asDouble());
            }
            Row best = sources.getRow(1);
            assertThat(best.getCell(1).getStringCellValue()).isEqualTo("Giới thiệu");
            assertThat(best.getCell(0).getNumericCellValue()).isEqualTo(1d);
            assertThat(best.getCell(8).getNumericCellValue()).isEqualTo(0.4d);
            assertThat(best.getCell(8).getCellStyle().getDataFormatString()).isEqualTo("0.00%");
            var hostile = cellWithText(sources, "=1+1");
            assertThat(hostile.getCellType()).isEqualTo(CellType.STRING);
            assertThat(hostile.getCellStyle().getQuotePrefixed()).isTrue();
            var unknown = cellWithText(sources, "Không rõ nguồn");
            assertThat(sources.getRow(unknown.getRowIndex()).getCell(3)).isNull();      // no catalog state to show
            var referrals = sources.getRow(cellWithText(sources, "Giới thiệu nội bộ").getRowIndex());
            assertThat(referrals.getCell(2).getStringCellValue()).isEqualTo("INTERNAL_REFERRAL");
            assertThat(referrals.getCell(3)).isNull();
            assertThat(referrals.getCell(4).getNumericCellValue()).isEqualTo(1d);
            assertThat(cellWithText(book.getSheet("Bộ lọc"), "Giới thiệu")).isNotNull(); // the best source
            book.forEach(sheet -> sheet.forEach(row -> row.forEach(cell ->
                    assertThat(cell.getCellType()).isNotEqualTo(CellType.FORMULA))));
        }
    }

    @Test
    void theExportFollowsTheScopeFiltersAndErrorsOfTheReport() throws Exception {
        arrange();
        try (Workbook book = WorkbookFactory.create(new ByteArrayInputStream(download(QUERY, recruiterToken).body()))) {
            Sheet sources = book.getSheet("Theo nguồn");
            sources.forEach(row -> row.forEach(cell -> {
                if (cell.getCellType() == CellType.STRING) {
                    assertThat(cell.getStringCellValue()).isNotEqualTo("=1+1");
                }
            }));
        }
        try (Workbook empty = WorkbookFactory.create(new ByteArrayInputStream(
                download("?from=2026-01-01&to=2026-01-31", hrToken).body()))) {
            assertThat(empty.getSheet("Theo nguồn").getLastRowNum()).isZero();
            assertThat(cellWithText(empty.getSheet("Bộ lọc"), "Chưa có nguồn đủ mẫu")).isNotNull();
        }
        assertThat(download(QUERY + "&minApplications=0", hrToken).statusCode()).isEqualTo(400);
        assertThat(download(QUERY + "&requisitionId=" + otherRequisition, recruiterToken).statusCode()).isEqualTo(403);
        assertThat(download(QUERY, interviewerToken).statusCode()).isEqualTo(403);
        assertThat(download(QUERY, null).statusCode()).isEqualTo(401);
    }
}
