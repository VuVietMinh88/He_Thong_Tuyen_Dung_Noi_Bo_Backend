package vn.ttcs.recruitment.auth;

import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import vn.ttcs.recruitment.application.ApplicationStage;
import vn.ttcs.recruitment.application.ApplicationSubmission;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 423 (story S6-01): the export uses the search's own criteria, checks and scope. The row limit is lowered to 4
 * here so the refusal of an oversized export can be shown with a handful of applications.
 */
@TestPropertySource(properties = "app.candidate-export.max-rows=4")
class CandidateExportIntegrationTest extends CandidateSearchTestSupport {
    static final String EXPORT = SEARCH + "/export";
    private final HttpClient binary = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    HttpResponse<byte[]> download(String token, String... pairs) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + EXPORT + query(pairs)))
                .timeout(Duration.ofSeconds(30)).GET();
        if (token != null) request.header("Authorization", "Bearer " + token);
        return binary.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    // The values of the "Họ tên" column below the header, in the file's order.
    static List<String> names(byte[] content) throws Exception {
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();
            int column = -1;
            for (var cell : sheet.getRow(0)) {
                if ("Họ tên".equals(formatter.formatCellValue(cell))) column = cell.getColumnIndex();
            }
            assertThat(column).as("name column").isNotNegative();
            List<String> names = new ArrayList<>();
            for (Row row : sheet) {
                if (row.getRowNum() > 0) names.add(formatter.formatCellValue(row.getCell(column)));
            }
            return names;
        }
    }

    HttpResponse<byte[]> okFile(HttpResponse<byte[]> response) {
        assertThat(response.statusCode()).as(new String(response.body())).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValue(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(v -> assertThat(v).contains("no-store"));
        assertThat(response.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
        assertThat(response.headers().firstValue("Content-Disposition")).hasValueSatisfying(v ->
                assertThat(v).startsWith("attachment;").contains(".xlsx"));
        return response;
    }

    @Test
    void exportHoldsExactlyTheRowsTheSearchListsInTheSameOrder() throws Exception {
        receivedAt(first.applicationId(), java.time.Instant.parse("2026-10-14T03:00:00Z"));
        assertThat(names(okFile(download(recruiterToken)).body())).containsExactly("Trần Thị Bình", "Nguyễn Văn A");
        assertThat(ids(search(recruiterToken))).containsExactly(binh.applicationId(), first.applicationId());
        assertThat(names(okFile(download(token(outsider))).body())).containsExactly("Nguyễn Văn A");
        assertThat(names(okFile(download(hrToken)).body())).hasSize(3);
        assertThat(names(okFile(download(hrToken, "keyword", "tran thi", "coverLetterKeyword", "spring")).body()))
                .containsExactly("Trần Thị Bình");
        assertThat(names(okFile(download(hrToken, "stage", "WITHDRAWN")).body())).containsExactly("Lê Văn Rút");
        rating(first.applicationId(), 5);
        assertThat(names(okFile(download(hrToken, "minRating", "5")).body())).containsExactly("Nguyễn Văn A");
        // Every exported application is logged like a shown one.
        assertThat(granted(recruiter)).isEqualTo(4);
    }

    @Test
    void exportAppliesTheSearchChecksBeforeReadingAnyApplication() throws Exception {
        assertThat(download(recruiterToken, "requisitionId", otherRequisition.toString()).statusCode()).isEqualTo(403);
        assertThat(download(recruiterToken, "requisitionId", UUID.randomUUID().toString()).statusCode())
                .isEqualTo(404);
        assertThat(download(hrToken, "minRating", "9").statusCode()).isEqualTo(400);
        assertThat(download(hrToken, "keyword", "a").statusCode()).isEqualTo(400);
        UUID noRole = applications.account("No role", "none@example.test");
        assertThat(download(token(noRole)).statusCode()).isEqualTo(403);
        assertThat(download(null).statusCode()).isEqualTo(401);
        assertThat(granted(recruiter)).isZero();
        var empty = okFile(download(recruiterToken, "stage", "OFFER"));
        assertThat(names(empty.body())).isEmpty();
    }

    @Test
    void anOversizedExportIsRefusedInsteadOfCutAndLogsNothing() throws Exception {
        var posting = postingOf(requisition);
        for (String email : List.of("c1@example.test", "c2@example.test", "c3@example.test")) {
            applications.stored(posting, new ApplicationSubmission("Ứng viên " + email.charAt(1), email, "0911000111",
                    null, null));
        }
        var tooLarge = call("GET", EXPORT, null, recruiterToken);
        assertError(tooLarge, 400, "CANDIDATE_EXPORT_TOO_LARGE");
        assertThat(granted(recruiter)).isZero();
        // Four rows still fit exactly.
        applications.stage(binh.applicationId(), ApplicationStage.REJECTED);
        assertThat(names(okFile(download(recruiterToken, "stage", "NEW")).body())).hasSize(4);
    }
}
