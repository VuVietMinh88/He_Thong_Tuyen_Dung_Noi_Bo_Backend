package vn.ttcs.recruitment.auth;

import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Task 424 (story S6-01): the file's name, its typed and safe cells, and what it never contains. */
class CandidateExportFormatIntegrationTest extends CandidateSearchTestSupport {
    private final HttpClient binary = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    HttpResponse<byte[]> download(String token, String... pairs) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + SEARCH + "/export" + query(pairs)))
                .timeout(Duration.ofSeconds(30)).GET().header("Authorization", "Bearer " + token);
        var response = binary.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).as(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo(200);
        return response;
    }

    static String disposition(HttpResponse<byte[]> response) {
        return response.headers().firstValue("Content-Disposition").orElseThrow();
    }

    // Every XML part of the package as text, so nothing can hide in a sheet, a comment or a property. Read through the
    // central directory (ZipFile): the streamed workbook writes each entry's size after its data.
    static String packageText(byte[] content) throws Exception {
        StringBuilder text = new StringBuilder();
        java.nio.file.Path file = java.nio.file.Files.createTempFile("candidate-export", ".xlsx");
        try {
            java.nio.file.Files.write(file, content);
            try (var zip = new java.util.zip.ZipFile(file.toFile())) {
                for (var entries = zip.entries(); entries.hasMoreElements(); ) {
                    try (var input = zip.getInputStream(entries.nextElement())) {
                        text.append(new String(input.readAllBytes(), StandardCharsets.UTF_8));
                    }
                }
            }
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
        return text.toString();
    }

    @Test
    void theFileIsNamedAfterThePositionCodeAndTheBusinessDateOfTheExport() throws Exception {
        jdbc.update("UPDATE positions SET code = 'DEV/JAVA 01' WHERE id = ?", positionOf(requisition));
        // 00:30 on 16 Oct in Vietnam is still 15 Oct in UTC; tokens are issued at the new time.
        clock.set(Instant.parse("2026-10-15T17:30:00Z"));
        String lateRecruiter = token(recruiter);
        String lateHr = token(hr);
        assertThat(disposition(download(lateRecruiter, "requisitionId", requisition.toString())))
                .contains("ung-vien_DEV-JAVA-01_2026-10-16.xlsx");
        assertThat(disposition(download(lateHr, "positionId", positionOf(otherRequisition).toString())))
                .contains("_2026-10-16.xlsx").contains("ung-vien_" + jdbc.queryForObject(
                        "SELECT code FROM positions WHERE id = ?", String.class, positionOf(otherRequisition)));
        assertThat(disposition(download(lateRecruiter))).contains("ung-vien_TAT-CA_2026-10-16.xlsx");
    }

    @Test
    void theFileNeverHoldsNotesCoverLettersTrackingDataOrOutOfScopeCandidates() throws Exception {
        jdbc.update("INSERT INTO application_screening_notes(id,application_id,content,author_id,created_at) VALUES (?,?,?,?,?)",
                UUID.randomUUID(), binh.applicationId(), "GHI CHÚ NỘI BỘ: lương mong muốn quá cao", recruiter,
                Timestamp.from(ApplicationTestSupport.NOW));
        rating(binh.applicationId(), 3);
        tagged(binh.applicationId(), tag);
        byte[] content = download(recruiterToken, "requisitionId", requisition.toString(), "sourceId",
                linkedIn.toString()).body();
        String all = packageText(content);
        assertThat(all).contains("Trần Thị Bình", "binh@example.test", "LinkedIn", "Nhãn nội bộ")
                .doesNotContain("GHI CHÚ", "lương mong muốn", "Tôi có 5 năm", "kinh nghiệm Java", binh.trackingCode(),
                        binh.applicationId().toString(), binh.candidateId().toString(), "lan@example.test",
                        "Nguyễn Văn A", "cv.pdf");
        try (var file = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            var formatter = new DataFormatter();
            var sheet = file.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(1);
            List<String> row = new ArrayList<>();
            sheet.getRow(1).forEach(cell -> row.add(formatter.formatCellValue(cell)));
            assertThat(row).containsExactly("1", jdbc.queryForObject("SELECT code FROM positions WHERE id = ?",
                    String.class, positionOf(requisition)), "Developer", "Java", "Trần Thị Bình", "binh@example.test",
                    "0987654321", "Hồ sơ mới", "LinkedIn", "Nhãn nội bộ", "3", "15/10/2026 10:00");
            var info = file.getSheetAt(1);
            List<String> labels = new ArrayList<>();
            List<String> values = new ArrayList<>();
            info.forEach(line -> {
                labels.add(formatter.formatCellValue(line.getCell(0)));
                values.add(formatter.formatCellValue(line.getCell(1)));
            });
            assertThat(labels).containsExactly("Thời điểm xuất", "Số hồ sơ", "Vị trí", "Giai đoạn", "Nguồn");
            assertThat(values.get(1)).isEqualTo("1");
            assertThat(values.get(3)).isEqualTo("Tất cả trừ Ứng viên rút");
            assertThat(values.get(4)).isEqualTo("LinkedIn");
        }
    }

    @Test
    void aStoredFormulaIsExportedAsQuotedText() throws Exception {
        jdbc.update("UPDATE job_applications SET full_name = '=HYPERLINK(\"http://evil.example\",\"Bấm\")' WHERE id = ?",
                binh.applicationId());
        byte[] content = download(recruiterToken, "keyword", "binh@example").body();
        try (var file = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            var cell = file.getSheetAt(0).getRow(1).getCell(4);
            assertThat(cell.getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.STRING);
            assertThat(cell.getStringCellValue()).startsWith("=HYPERLINK(");
            assertThat(cell.getCellStyle().getQuotePrefixed()).isTrue();
        }
        assertThat(packageText(content)).doesNotContain("<f>");
    }
}
