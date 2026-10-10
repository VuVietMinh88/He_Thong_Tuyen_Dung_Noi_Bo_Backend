package vn.ttcs.recruitment.auth;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.account.BootstrapAdmin;
import vn.ttcs.recruitment.account.Role;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The summary report of POST /api/v1/accounts/import: how many rows were read, created and skipped, which rows
 * they were in file order, and why every skipped row got no account.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "spring.mail.host=127.0.0.1", "spring.mail.properties.mail.smtp.auth=false",
        "spring.mail.properties.mail.smtp.starttls.enable=false",
        "spring.mail.properties.mail.smtp.starttls.required=false",
        "app.account-activation.page-url=http://localhost:5173/activate-account",
        "app.account-activation.ttl=24h", "app.password-reset.mail-from=no-reply@ttcs.test",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StaffImportReportIntegrationTest {
    private static final String IMPORT = "/api/v1/accounts/import";
    private static final String PREVIEW = "/api/v1/accounts/import/preview";
    private static final String PASSWORD = "TestingOnly123!";
    private static final String ADMIN_EMAIL = "admin@example.test";
    private static final Instant START = Instant.parse("2026-10-07T00:00:00Z");
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final List<String> HEADERS = List.of(
            "Email", "Họ và tên", "Vai trò", "Mã phòng ban", "Số điện thoại", "Chức danh hiển thị");
    private static final LocalSmtpServer SMTP = startSmtp();

    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccountRepository accounts;
    @Autowired private BootstrapAdmin bootstrap;
    @Autowired private AuthIntegrationTest.MutableClock clock;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private UUID adminId;
    private String adminToken;
    private String fixturePasswordHash;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        String key = Base64.getEncoder().encodeToString(secret);
        registry.add("app.auth.jwt-secret", () -> key);
        registry.add("spring.mail.port", SMTP::port);
    }

    @BeforeEach
    void resetFixture() throws Exception {
        clock.set(START);
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("UPDATE user_accounts SET department_id = NULL, admin_locked_at = NULL, admin_lock_reason = NULL, admin_locked_by = NULL");
        jdbc.update("UPDATE departments SET parent_id = NULL");
        jdbc.update("DELETE FROM departments");
        jdbc.update("DELETE FROM user_accounts");
        bootstrap.run(new DefaultApplicationArguments());
        SMTP.reset();
        JsonNode login = login(ADMIN_EMAIL);
        adminId = UUID.fromString(login.path("user").path("id").asText());
        adminToken = login.path("accessToken").asText();
        fixturePasswordHash = accounts.findById(adminId).orElseThrow().getPasswordHash();
        department("HR", true);
        department("IT", true);
        department("OLD", false);
    }

    @AfterAll
    static void closeSmtp() throws IOException {
        SMTP.close();
    }

    @Test
    void theReportCountsTheRowsAndGivesEverySkippedRowTheErrorsThePreviewShows() throws Exception {
        byte[] file = staffFile(
                row("an@example.com", "Nguyễn Văn An", "RECRUITER", "HR"),
                row(" Sai-Email ", null, "BOSS", "OLD"),
                null,
                row("binh@example.com", "Trần Thị Bình", "INTERVIEWER"),
                row("dup@example.com", "Trùng 1", "RECRUITER"),
                row("DUP@example.com", "Trùng 2", "RECRUITER"),
                row("cuong@example.com", "Lê Văn Cường", "APPROVER", "IT", "0912345678"),
                row(ADMIN_EMAIL, "Trùng Admin", "ADMIN"),
                row(null, "Không Email", "RECRUITER"));
        JsonNode preview = expectOk(upload(PREVIEW, adminToken, file));

        JsonNode report = expectOk(upload(IMPORT, adminToken, file));

        // The empty row 4 is not a person, so it is neither counted nor listed.
        assertThat(new ArrayList<>(report.propertyNames())).containsExactly(
                "totalRows", "createdCount", "skippedCount", "stoppedAtRow", "created", "skipped");
        assertSummary(report, 8, 3, 5);
        assertThat(report.path("totalRows").asInt()).isEqualTo(preview.path("totalRows").asInt());
        assertThat(report.path("stoppedAtRow").isNull()).isTrue();

        // Created rows in file order, each with the id of the account that now exists.
        assertThat(rowNumbers(report, "created")).containsExactly(2, 5, 8);
        for (JsonNode created : report.path("created")) {
            assertThat(new ArrayList<>(created.propertyNames())).containsExactly("rowNumber", "email", "accountId");
            assertThat(created.path("accountId").asText()).isEqualTo(
                    jdbc.queryForObject("SELECT id FROM user_accounts WHERE email = ?", UUID.class,
                            created.path("email").asText()).toString());
        }
        assertThat(report.path("created").findValuesAsString("email"))
                .containsExactly("an@example.com", "binh@example.com", "cuong@example.com");

        // Skipped rows in file order. Their errors are exactly the preview's: same codes, cells and messages.
        assertThat(rowNumbers(report, "skipped")).containsExactly(3, 6, 7, 9, 10);
        for (JsonNode skipped : report.path("skipped")) {
            assertThat(new ArrayList<>(skipped.propertyNames())).containsExactly("rowNumber", "email", "errors");
            JsonNode previewRow = previewRow(preview, skipped.path("rowNumber").asInt());
            assertThat(previewRow.path("valid").asBoolean()).isFalse();
            assertThat(skipped.path("email")).isEqualTo(previewRow.path("email"));
            assertThat(skipped.path("errors")).isEqualTo(previewRow.path("errors"));
        }
        // Errors stay in column order A→F, one per column.
        JsonNode wrongRow = skippedRow(report, 3);
        assertThat(wrongRow.path("email").asText()).isEqualTo("sai-email");
        assertThat(codes(wrongRow)).containsExactly("EMAIL_INVALID", "REQUIRED", "ROLE_UNKNOWN", "DEPARTMENT_INACTIVE");
        assertThat(wrongRow.path("errors").findValuesAsString("cell")).containsExactly("A3", "B3", "C3", "D3");
        assertThat(codes(skippedRow(report, 6))).containsExactly("EMAIL_DUPLICATED_IN_FILE");
        assertThat(codes(skippedRow(report, 7))).containsExactly("EMAIL_DUPLICATED_IN_FILE");
        assertThat(codes(skippedRow(report, 9))).containsExactly("EMAIL_ALREADY_EXISTS");
        JsonNode noEmail = skippedRow(report, 10);
        assertThat(noEmail.path("email").isNull()).isTrue();
        assertThat(codes(noEmail)).containsExactly("REQUIRED");

        // The report matches what was really written and sent.
        assertThat(emails()).containsExactlyInAnyOrder(ADMIN_EMAIL, "an@example.com", "binh@example.com",
                "cuong@example.com");
        assertThat(SMTP.messages()).hasSize(3);
    }

    @Test
    void aFileWithoutAnyValidRowStillGetsAReportWithAnEmptyCreatedList() throws Exception {
        byte[] file = staffFile(
                row("sai-email", "Sai", "RECRUITER"),
                row(ADMIN_EMAIL, "Trùng Admin", "ADMIN"));

        JsonNode report = expectOk(upload(IMPORT, adminToken, file));

        assertSummary(report, 2, 0, 2);
        assertThat(report.path("created").isArray()).isTrue();
        assertThat(rowNumbers(report, "skipped")).containsExactly(2, 3);
        assertThat(codes(skippedRow(report, 2))).containsExactly("EMAIL_INVALID");
        assertThat(codes(skippedRow(report, 3))).containsExactly("EMAIL_ALREADY_EXISTS");
        assertThat(emails()).containsExactly(ADMIN_EMAIL);
        assertThat(SMTP.messages()).isEmpty();
    }

    @Test
    void aFileWithOnlyValidRowsGetsAReportWithAnEmptySkippedList() throws Exception {
        byte[] file = staffFile(
                row("an@example.com", "An", "RECRUITER"),
                row("binh@example.com", "Bình", "INTERVIEWER", "HR"));

        JsonNode report = expectOk(upload(IMPORT, adminToken, file));

        assertSummary(report, 2, 2, 0);
        assertThat(report.path("skipped").isArray()).isTrue();
        assertThat(rowNumbers(report, "created")).containsExactly(2, 3);
        assertThat(report.path("stoppedAtRow").isNull()).isTrue();
    }

    @Test
    void aValidRowThatCannotBeCreatedAnyMoreIsReportedOnTheCellThatChanged() throws Exception {
        byte[] file = staffFile(
                row("first@example.com", "First", "RECRUITER"),
                row("taken@example.com", "Taken", "RECRUITER"),
                row("moved@example.com", "Moved", "RECRUITER", "IT"),
                row("last@example.com", "Last", "RECRUITER", "HR"));
        HttpResponse<String> response;
        SMTP.pauseReceipt();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var importing = executor.submit(() -> upload(IMPORT, adminToken, file));
            try {
                // The first invitation is being delivered, so every row was already checked and found valid.
                assertThat(SMTP.awaitData()).isTrue();
                accounts.saveAndFlush(Account.pendingActivation("taken@example.com", "Someone else",
                        fixturePasswordHash, Set.of(Role.APPROVER), START));
                jdbc.update("UPDATE departments SET active = FALSE WHERE code = 'IT'");
            } finally {
                SMTP.releaseReceipt();
            }
            response = importing.get(30, TimeUnit.SECONDS);
        }

        JsonNode report = expectOk(response);
        assertSummary(report, 4, 2, 2);
        assertThat(rowNumbers(report, "created")).containsExactly(2, 5);
        assertThat(rowNumbers(report, "skipped")).containsExactly(3, 4);

        JsonNode taken = onlyError(skippedRow(report, 3));
        assertThat(taken.path("rowNumber").asInt()).isEqualTo(3);
        assertThat(taken.path("column").asText()).isEqualTo("email");
        assertThat(taken.path("cell").asText()).isEqualTo("A3");
        assertThat(taken.path("code").asText()).isEqualTo("EMAIL_ALREADY_EXISTS");
        assertThat(taken.path("message").asText()).contains("trong lúc nhập");

        JsonNode moved = onlyError(skippedRow(report, 4));
        assertThat(moved.path("column").asText()).isEqualTo("departmentCode");
        assertThat(moved.path("cell").asText()).isEqualTo("D4");
        assertThat(moved.path("code").asText()).isEqualTo("INVALID_DEPARTMENT");
        assertThat(moved.path("message").asText()).contains("\"IT\"");
    }

    @Test
    void anAddressTheMailServerRefusesIsReportedOnItsEmailCell() throws Exception {
        byte[] file = staffFile(
                row("an@example.com", "An", "RECRUITER"),
                row("binh@example.com", "Bình", "RECRUITER"),
                row("cuong@example.com", "Cường", "RECRUITER"));
        SMTP.rejectRecipient("binh@example.com");

        JsonNode report = expectOk(upload(IMPORT, adminToken, file));

        assertSummary(report, 3, 2, 1);
        assertThat(rowNumbers(report, "created")).containsExactly(2, 4);
        assertThat(report.path("stoppedAtRow").isNull()).isTrue();
        JsonNode refused = onlyError(skippedRow(report, 3));
        assertThat(refused.path("column").asText()).isEqualTo("email");
        assertThat(refused.path("cell").asText()).isEqualTo("A3");
        assertThat(refused.path("code").asText()).isEqualTo("EMAIL_ADDRESS_REFUSED");
        // The SMTP reply itself is never shown.
        assertThat(report.toString()).doesNotContain("550", "Mailbox");
    }

    @Test
    void aMailServerThatStopsWorkingIsReportedOnItsRowAndOnEveryValidRowNotTriedAfterIt() throws Exception {
        byte[] file = staffFile(
                row("an@example.com", "An", "RECRUITER"),
                row("binh@example.com", "Bình", "RECRUITER"),
                row("sai-email", "Sai", "RECRUITER"),
                row("cuong@example.com", "Cường", "RECRUITER"),
                row("dung@example.com", "Dũng", "RECRUITER"));
        SMTP.rejectDeliveryAfter(1);

        JsonNode report = expectOk(upload(IMPORT, adminToken, file));

        assertSummary(report, 5, 1, 4);
        assertThat(report.path("stoppedAtRow").asInt()).isEqualTo(3);
        assertThat(rowNumbers(report, "created")).containsExactly(2);
        assertThat(rowNumbers(report, "skipped")).containsExactly(3, 4, 5, 6);

        // The row where the mail server failed: the problem is not in any cell.
        JsonNode stopped = onlyError(skippedRow(report, 3));
        assertThat(stopped.path("code").asText()).isEqualTo("ACCOUNT_EMAIL_UNAVAILABLE");
        assertThat(stopped.has("column")).isTrue();
        assertThat(stopped.path("column").isNull()).isTrue();
        assertThat(stopped.path("cell").isNull()).isTrue();
        // An invalid row after the stop keeps its own errors ...
        assertThat(codes(skippedRow(report, 4))).containsExactly("EMAIL_INVALID");
        // ... and every valid row after it says it was not tried and where the import stopped.
        for (int rowNumber : List.of(5, 6)) {
            JsonNode notTried = onlyError(skippedRow(report, rowNumber));
            assertThat(notTried.path("rowNumber").asInt()).isEqualTo(rowNumber);
            assertThat(notTried.path("code").asText()).isEqualTo("NOT_ATTEMPTED");
            assertThat(notTried.path("column").isNull()).isTrue();
            assertThat(notTried.path("cell").isNull()).isTrue();
            assertThat(notTried.path("message").asText()).contains("dòng 3");
        }
        assertThat(emails()).containsExactlyInAnyOrder(ADMIN_EMAIL, "an@example.com");
        assertThat(report.toString()).doesNotContain("451", "Temporary");
    }

    @Test
    void someoneWithoutTheImportRightGetsNoReport() throws Exception {
        accounts.saveAndFlush(new Account("hr.manager@example.test", "HR Manager", fixturePasswordHash,
                Set.of(Role.HR_MANAGER), START));
        String token = login("hr.manager@example.test").path("accessToken").asText();
        byte[] file = staffFile(row("an@example.com", "An", "RECRUITER"));

        HttpResponse<String> response = upload(IMPORT, token, file);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(403);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("code").asText()).isEqualTo("FORBIDDEN");
        assertThat(body.has("totalRows")).isFalse();
        assertThat(body.has("created")).isFalse();
        assertThat(body.has("skipped")).isFalse();
        assertThat(emails()).containsExactlyInAnyOrder(ADMIN_EMAIL, "hr.manager@example.test");
        assertThat(SMTP.messages()).isEmpty();
    }

    // Checks the counts and that they match the lists; each test then checks which rows are in each list.
    private static void assertSummary(JsonNode report, int totalRows, int createdCount, int skippedCount) {
        assertThat(report.path("totalRows").asInt()).as(report.toString()).isEqualTo(totalRows);
        assertThat(report.path("createdCount").asInt()).as(report.toString()).isEqualTo(createdCount);
        assertThat(report.path("skippedCount").asInt()).as(report.toString()).isEqualTo(skippedCount);
        assertThat(report.path("created").size()).isEqualTo(createdCount);
        assertThat(report.path("skipped").size()).isEqualTo(skippedCount);
        for (JsonNode skipped : report.path("skipped")) {
            assertThat(skipped.path("errors").isEmpty()).as("a skipped row always says why: " + skipped).isFalse();
        }
    }

    private static List<Integer> rowNumbers(JsonNode report, String list) {
        List<Integer> numbers = new ArrayList<>();
        for (JsonNode row : report.path(list)) {
            numbers.add(row.path("rowNumber").asInt());
        }
        return numbers;
    }

    private static JsonNode skippedRow(JsonNode report, int rowNumber) {
        for (JsonNode row : report.path("skipped")) {
            if (row.path("rowNumber").asInt() == rowNumber) {
                return row;
            }
        }
        throw new AssertionError("Row " + rowNumber + " is not skipped in " + report);
    }

    private static JsonNode previewRow(JsonNode preview, int rowNumber) {
        for (JsonNode row : preview.path("rows")) {
            if (row.path("rowNumber").asInt() == rowNumber) {
                return row;
            }
        }
        throw new AssertionError("No row " + rowNumber + " in " + preview);
    }

    private static List<String> codes(JsonNode skippedRow) {
        return skippedRow.path("errors").findValuesAsString("code");
    }

    private static JsonNode onlyError(JsonNode skippedRow) {
        assertThat(skippedRow.path("errors").size()).as(skippedRow.toString()).isEqualTo(1);
        return skippedRow.path("errors").path(0);
    }

    private JsonNode expectOk(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        return json.readTree(response.body());
    }

    private List<String> emails() {
        return jdbc.queryForList("SELECT email FROM user_accounts", String.class);
    }

    private void department(String code, boolean active) {
        jdbc.update("INSERT INTO departments (id, code, name, manager_user_id, active, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), code, "Phòng " + code, adminId, active, Timestamp.from(START));
    }

    // Row 1 gets the template header; each array is the next row. A null array leaves that row empty.
    private static byte[] staffFile(Object[]... rows) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Nhân sự");
            write(sheet.createRow(0), HEADERS.toArray());
            for (int index = 0; index < rows.length; index++) {
                if (rows[index] != null) {
                    write(sheet.createRow(index + 1), rows[index]);
                }
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }

    // Values start at column A; null leaves the cell out.
    private static void write(Row row, Object... values) {
        for (int column = 0; column < values.length; column++) {
            if (values[column] != null) {
                row.createCell(column).setCellValue(values[column].toString());
            }
        }
    }

    private static Object[] row(Object... values) {
        return values;
    }

    private HttpResponse<String> upload(String path, String token, byte[] content) throws Exception {
        String boundary = "ttcs-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"nhan-su.xlsx\"\r\n"
                + "Content-Type: " + XLSX + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(content);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        var request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(60))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode login(String email) throws Exception {
        var request = HttpRequest.newBuilder(uri("/api/v1/auth/login")).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + environment.getRequiredProperty("local.server.port") + path);
    }

    private static LocalSmtpServer startSmtp() {
        try {
            return new LocalSmtpServer();
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
