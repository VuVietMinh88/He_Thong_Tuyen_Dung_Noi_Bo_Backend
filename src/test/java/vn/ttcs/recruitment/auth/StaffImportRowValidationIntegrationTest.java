package vn.ttcs.recruitment.auth;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.account.BootstrapAdmin;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.account.importing.StaffImportCheckedRow;
import vn.ttcs.recruitment.account.importing.StaffImportPreview;
import vn.ttcs.recruitment.account.importing.StaffImportRowError;
import vn.ttcs.recruitment.account.importing.StaffImportService;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Per-row checks of the staff import preview (POST /api/v1/accounts/import/preview). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StaffImportRowValidationIntegrationTest {
    private static final String PREVIEW = "/api/v1/accounts/import/preview";
    private static final String PASSWORD = "TestingOnly123!";
    private static final Instant START = Instant.parse("2026-10-07T00:00:00Z");
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final List<String> HEADERS = List.of(
            "Email", "Họ và tên", "Vai trò", "Mã phòng ban", "Số điện thoại", "Chức danh hiển thị");
    private static final List<String> STATE_TABLES = List.of(
            "user_accounts", "user_roles", "account_activation_tokens", "departments");
    private static final String EMAIL_USED = "Email đã được sử dụng cho một tài khoản nội bộ.";

    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccountRepository accounts;
    @Autowired private BootstrapAdmin bootstrap;
    @Autowired private AuthIntegrationTest.MutableClock clock;
    @Autowired private StaffImportService service;

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
        JsonNode login = login("admin@example.test");
        adminId = UUID.fromString(login.path("user").path("id").asText());
        adminToken = login.path("accessToken").asText();
        fixturePasswordHash = accounts.findById(adminId).orElseThrow().getPasswordHash();
        department("HR", true);
        department("OLD", false);
    }

    @Test
    void aFileWithoutProblemsHasOnlyValidRowsAndCreatesNothing() throws Exception {
        byte[] file = staffFile(
                row("an@example.com", "Nguyễn Văn An", "RECRUITER, INTERVIEWER", "HR", "0912345678", "Chuyên viên"),
                row("binh@example.com", "Trần Thị Bình", "HR_MANAGER"));
        Map<String, List<Map<String, Object>>> before = snapshot();

        JsonNode body = expectOk(upload(adminToken, file));

        assertThat(body.path("totalRows").asInt()).isEqualTo(2);
        assertThat(body.path("validRows").asInt()).isEqualTo(2);
        assertThat(body.path("invalidRows").asInt()).isZero();
        for (JsonNode row : body.path("rows")) {
            assertThat(row.path("valid").asBoolean()).as(row.toString()).isTrue();
            // A valid row has an empty list, never a missing or null one.
            assertThat(row.path("errors").isArray()).as(row.toString()).isTrue();
            assertThat(row.path("errors")).isEmpty();
        }
        // Checking only reads: no account, role, activation token (so no invitation e-mail) or department changes.
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void missingRequiredCellsAreReportedWithRowColumnCellAndVietnameseMessage() throws Exception {
        byte[] file = staffFile(
                row(null, null, null, null, null, "Chuyên viên"),
                row("an@example.com", "   ", ", ,"),
                row("binh@example.com", "Trần Thị Bình", "RECRUITER"));

        JsonNode body = expectOk(upload(adminToken, file));

        assertThat(body.path("totalRows").asInt()).isEqualTo(3);
        assertThat(body.path("validRows").asInt()).isEqualTo(1);
        assertThat(body.path("invalidRows").asInt()).isEqualTo(2);
        JsonNode onlyOptionalCell = excelRow(body, 2);
        assertThat(onlyOptionalCell.path("valid").asBoolean()).isFalse();
        assertThat(onlyOptionalCell.path("errors")).isEqualTo(json.readTree(json.writeValueAsString(List.of(
                error(2, "email", "A2", "REQUIRED", "Email là bắt buộc."),
                error(2, "fullName", "B2", "REQUIRED", "Họ và tên là bắt buộc."),
                error(2, "roles", "C2", "REQUIRED", "Vai trò là bắt buộc.")))));
        // A name of only spaces is empty, and a roles cell of only commas has no role.
        assertThat(codes(excelRow(body, 3))).containsExactly("B3 REQUIRED", "C3 REQUIRED");
        assertThat(codes(excelRow(body, 4))).isEmpty();
    }

    @Test
    void emailMustBeWellFormedAndNotUsedByAnyExistingAccount() throws Exception {
        accounts.saveAndFlush(Account.pendingActivation("pending@example.test", "Pending", fixturePasswordHash,
                Set.of(Role.RECRUITER), START));
        byte[] file = staffFile(
                row("not-an-email", "A", "RECRUITER"),
                row("an nguyen@example.com", "A", "RECRUITER"),
                row("an@@example.com", "A", "RECRUITER"),
                row("@example.com", "A", "RECRUITER"),
                row("  ADMIN@Example.TEST ", "A", "RECRUITER"),
                row("pending@example.test", "A", "RECRUITER"),
                row("an.nguyen+tuyen-dung@example.com.vn", "A", "RECRUITER"));
        Map<String, List<Map<String, Object>>> before = snapshot();

        JsonNode body = expectOk(upload(adminToken, file));

        for (int rowNumber = 2; rowNumber <= 5; rowNumber++) {
            assertThat(codes(excelRow(body, rowNumber))).containsExactly("A" + rowNumber + " EMAIL_INVALID");
        }
        assertThat(message(excelRow(body, 2), "email"))
                .isEqualTo("Email không đúng định dạng, ví dụ đúng: nguyen.van.an@example.com.");
        // Spaces and upper case do not hide an existing account: the email is normalized first.
        assertThat(excelRow(body, 6).path("email").asText()).isEqualTo("admin@example.test");
        assertThat(codes(excelRow(body, 6))).containsExactly("A6 EMAIL_ALREADY_EXISTS");
        assertThat(message(excelRow(body, 6), "email")).isEqualTo(EMAIL_USED);
        // An account waiting for activation already owns its email too.
        assertThat(codes(excelRow(body, 7))).containsExactly("A7 EMAIL_ALREADY_EXISTS");
        assertThat(codes(excelRow(body, 8))).isEmpty();
        assertThat(body.path("validRows").asInt()).isEqualTo(1);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void everyRowOfARepeatedEmailNamesTheOtherRows() throws Exception {
        byte[] file = staffFile(
                row("an@example.com", "An 1", "RECRUITER"),
                row("binh@example.com", "Bình", "RECRUITER"),
                row(" AN@example.com ", "An 2", "INTERVIEWER"),
                null,
                row("an@EXAMPLE.com", "An 3", "APPROVER"),
                row("admin@example.test", "Admin 1", "ADMIN"),
                row("Admin@example.test", "Admin 2", "ADMIN"),
                row("sai-email", "X", "RECRUITER"),
                row("sai-email", "Y", "RECRUITER"));

        JsonNode body = expectOk(upload(adminToken, file));

        assertThat(body.path("totalRows").asInt()).isEqualTo(8);
        assertThat(message(excelRow(body, 2), "email"))
                .isEqualTo("Email này cũng có ở dòng 4, 6 của tệp. Mỗi email chỉ dùng cho một người.");
        assertThat(message(excelRow(body, 4), "email")).contains("dòng 2, 6 ");
        assertThat(message(excelRow(body, 6), "email")).contains("dòng 2, 4 ");
        for (int rowNumber : List.of(2, 4, 6)) {
            assertThat(codes(excelRow(body, rowNumber))).containsExactly("A" + rowNumber + " EMAIL_DUPLICATED_IN_FILE");
        }
        assertThat(codes(excelRow(body, 3))).isEmpty();
        // An email that already has an account is reported as such, even when it is also repeated.
        assertThat(codes(excelRow(body, 7))).containsExactly("A7 EMAIL_ALREADY_EXISTS");
        assertThat(codes(excelRow(body, 8))).containsExactly("A8 EMAIL_ALREADY_EXISTS");
        // A wrong email is reported once, as wrong, not also as repeated.
        assertThat(codes(excelRow(body, 9))).containsExactly("A9 EMAIL_INVALID");
        assertThat(codes(excelRow(body, 10))).containsExactly("A10 EMAIL_INVALID");
        assertThat(body.path("validRows").asInt()).isEqualTo(1);
        assertThat(body.path("invalidRows").asInt()).isEqualTo(7);
    }

    @Test
    void rolesMustBeInternalRoleCodes() throws Exception {
        byte[] file = staffFile(
                row("a@example.com", "A", "recruiter"),
                row("b@example.com", "B", "CANDIDATE"),
                row("c@example.com", "C", "ADMIN, boss, Hr Manager, ADMIN"),
                row("d@example.com", "D", "HR_MANAGER,HIRING_MANAGER,INTERVIEWER,APPROVER,RECRUITER,ADMIN"));

        JsonNode body = expectOk(upload(adminToken, file));

        assertThat(codes(excelRow(body, 2))).isEmpty();
        assertThat(codes(excelRow(body, 3))).containsExactly("C3 ROLE_UNKNOWN");
        assertThat(message(excelRow(body, 3), "roles")).isEqualTo("Mã vai trò không hợp lệ: CANDIDATE. Chỉ dùng các mã: "
                + "ADMIN, HR_MANAGER, RECRUITER, HIRING_MANAGER, INTERVIEWER, APPROVER.");
        // Only the wrong codes are named; known codes in the same cell are fine.
        assertThat(message(excelRow(body, 4), "roles")).startsWith("Mã vai trò không hợp lệ: BOSS, HR MANAGER. ");
        assertThat(codes(excelRow(body, 5))).isEmpty();
    }

    @Test
    void departmentCodeMustNameAnActiveDepartmentWithTheSameCase() throws Exception {
        String longestCode = "P".repeat(50);
        department(longestCode, true);
        byte[] file = staffFile(
                row("a@example.com", "A", "RECRUITER", "HR"),
                row("b@example.com", "B", "RECRUITER", "hr"),
                row("c@example.com", "C", "RECRUITER", "OLD"),
                row("d@example.com", "D", "RECRUITER", "KHONG_CO"),
                row("e@example.com", "E", "RECRUITER", longestCode),
                row("f@example.com", "F", "RECRUITER", longestCode + "P"),
                row("g@example.com", "G", "RECRUITER", null, "0912345678"));

        JsonNode body = expectOk(upload(adminToken, file));

        assertThat(codes(excelRow(body, 2))).isEmpty();
        assertThat(codes(excelRow(body, 3))).containsExactly("D3 DEPARTMENT_NOT_FOUND");
        assertThat(message(excelRow(body, 3), "departmentCode"))
                .isEqualTo("Không có phòng ban mã \"hr\". Mã phân biệt chữ hoa/thường, hãy ghi đúng như danh mục phòng ban.");
        assertThat(codes(excelRow(body, 4))).containsExactly("D4 DEPARTMENT_INACTIVE");
        assertThat(message(excelRow(body, 4), "departmentCode"))
                .isEqualTo("Phòng ban mã \"OLD\" đã ngừng áp dụng, không gán được nhân sự mới.");
        assertThat(codes(excelRow(body, 5))).containsExactly("D5 DEPARTMENT_NOT_FOUND");
        assertThat(codes(excelRow(body, 6))).isEmpty();
        assertThat(codes(excelRow(body, 7))).containsExactly("D7 TOO_LONG");
        assertThat(message(excelRow(body, 7), "departmentCode")).isEqualTo("Mã phòng ban tối đa 50 ký tự, ô này có 51 ký tự.");
        // The department is optional.
        assertThat(codes(excelRow(body, 8))).isEmpty();
    }

    @Test
    void phoneMustBeAVietnameseMobileOrLandlineNumber() throws Exception {
        List<Object> valid = List.of("0912345678", "+84912345678", "0312345678", "02123456789");
        List<Object> invalid = List.of("0212345678", "0912 345 678", 912345678, "0112345678", "091234567a",
                "09123456789");
        List<Object[]> rows = new ArrayList<>();
        for (Object phone : valid) {
            rows.add(row("valid" + rows.size() + "@example.com", "A", "RECRUITER", null, phone));
        }
        for (Object phone : invalid) {
            rows.add(row("invalid" + rows.size() + "@example.com", "A", "RECRUITER", null, phone));
        }

        JsonNode body = expectOk(upload(adminToken, staffFile(rows.toArray(Object[][]::new))));

        for (int index = 0; index < valid.size(); index++) {
            assertThat(codes(excelRow(body, index + 2))).as(valid.get(index).toString()).isEmpty();
        }
        assertThat(excelRow(body, 3).path("phone").asText()).isEqualTo("0912345678");
        for (int index = valid.size(); index < rows.size(); index++) {
            assertThat(codes(excelRow(body, index + 2))).as(invalid.get(index - valid.size()).toString())
                    .containsExactly("E" + (index + 2) + " PHONE_INVALID");
        }
        assertThat(message(excelRow(body, valid.size() + 2), "phone"))
                .contains("10 chữ số", "03, 05, 07, 08, 09", "11 chữ số bắt đầu bằng 02", "định dạng ô là Text");
    }

    @Test
    void lengthLimitsMatchAccountCreationAndProfileRules() throws Exception {
        String longestEmail = "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(58) + ".vn";
        String tooLongEmail = "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(59) + ".vn";
        assertThat(longestEmail).hasSize(254);
        byte[] file = staffFile(
                row(longestEmail, "Ễ".repeat(255), "RECRUITER", null, null, "Đ".repeat(120)),
                row(tooLongEmail, "Ễ".repeat(256), "RECRUITER", null, null, "Đ".repeat(121)));

        JsonNode body = expectOk(upload(adminToken, file));

        assertThat(codes(excelRow(body, 2))).isEmpty();
        JsonNode tooLong = excelRow(body, 3);
        assertThat(codes(tooLong)).containsExactly("A3 TOO_LONG", "B3 TOO_LONG", "F3 TOO_LONG");
        assertThat(message(tooLong, "email")).isEqualTo("Email tối đa 254 ký tự, ô này có 255 ký tự.");
        assertThat(message(tooLong, "fullName")).isEqualTo("Họ và tên tối đa 255 ký tự, ô này có 256 ký tự.");
        assertThat(message(tooLong, "displayTitle")).isEqualTo("Chức danh hiển thị tối đa 120 ký tự, ô này có 121 ký tự.");
    }

    @Test
    void everyPreviewChecksTheAccountsAndDepartmentsAsTheyAreNow() throws Exception {
        byte[] file = staffFile(row("late@example.com", "Late", "RECRUITER", "HR"));
        assertThat(codes(excelRow(expectOk(upload(adminToken, file)), 2))).isEmpty();

        // Someone creates the account and stops using the department between two previews of the same file.
        UUID late = accounts.saveAndFlush(new Account("late@example.com", "Late", fixturePasswordHash,
                Set.of(Role.RECRUITER), START)).getId();
        jdbc.update("UPDATE departments SET active = FALSE WHERE code = 'HR'");
        assertThat(codes(excelRow(expectOk(upload(adminToken, file)), 2)))
                .containsExactly("A2 EMAIL_ALREADY_EXISTS", "D2 DEPARTMENT_INACTIVE");

        accounts.deleteById(late);
        jdbc.update("DELETE FROM departments WHERE code = 'HR'");
        assertThat(codes(excelRow(expectOk(upload(adminToken, file)), 2))).containsExactly("D2 DEPARTMENT_NOT_FOUND");
    }

    @Test
    void onlyAdministratorsWithUserAdminWriteLearnWhichEmailsHaveAccounts() throws Exception {
        accounts.saveAndFlush(new Account("hr@example.test", "HR", fixturePasswordHash, Set.of(Role.HR_MANAGER), START));
        accounts.saveAndFlush(new Account("recruiter@example.test", "Recruiter", fixturePasswordHash,
                Set.of(Role.RECRUITER), START));
        String hrToken = login("hr@example.test").path("accessToken").asText();
        String recruiterToken = login("recruiter@example.test").path("accessToken").asText();
        byte[] file = staffFile(row("hr@example.test", "Someone", "RECRUITER"));

        for (String token : List.of(hrToken, recruiterToken)) {
            var response = upload(token, file);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(403);
            assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo("FORBIDDEN");
            assertThat(response.body()).doesNotContain("EMAIL_ALREADY_EXISTS").doesNotContain("rows");
        }
        var anonymous = upload(null, file);
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(anonymous.body()).doesNotContain("EMAIL_ALREADY_EXISTS");

        assertThat(codes(excelRow(expectOk(upload(adminToken, file)), 2))).containsExactly("A2 EMAIL_ALREADY_EXISTS");
    }

    @Test
    void serviceReturnsTheCheckedRowsAsRecordsForTheImportStep() throws Exception {
        Jwt admin = jwt(adminId, sessionOf(adminId), START.plus(TokenService.ACCESS_TOKEN_TTL));
        var file = new MockMultipartFile("file", "a.xlsx", XLSX, staffFile(
                row("admin@example.test", "A", "RECRUITER"),
                row("b@example.com", "B", "RECRUITER", "HR")));

        StaffImportPreview preview = service.preview(admin, file);

        assertThat(preview).isEqualTo(new StaffImportPreview(2, 1, 1, List.of(
                new StaffImportCheckedRow(2, "admin@example.test", "A", List.of("RECRUITER"), null, null, null, false,
                        List.of(new StaffImportRowError(2, "email", "A2", "EMAIL_ALREADY_EXISTS", EMAIL_USED))),
                new StaffImportCheckedRow(3, "b@example.com", "B", List.of("RECRUITER"), "HR", null, null, true,
                        List.of()))));
    }

    private static Map<String, Object> error(int rowNumber, String column, String cell, String code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("rowNumber", rowNumber);
        error.put("column", column);
        error.put("cell", cell);
        error.put("code", code);
        error.put("message", message);
        return error;
    }

    // "A3 EMAIL_INVALID" for each error of the row, in response order.
    private static List<String> codes(JsonNode row) {
        List<String> codes = new ArrayList<>();
        for (JsonNode error : row.path("errors")) {
            assertThat(error.path("rowNumber").asInt()).isEqualTo(row.path("rowNumber").asInt());
            codes.add(error.path("cell").asText() + " " + error.path("code").asText());
        }
        assertThat(row.path("valid").asBoolean()).isEqualTo(codes.isEmpty());
        return codes;
    }

    private static String message(JsonNode row, String column) {
        for (JsonNode error : row.path("errors")) {
            if (error.path("column").asText().equals(column)) {
                return error.path("message").asText();
            }
        }
        throw new AssertionError("No " + column + " error in " + row);
    }

    private static JsonNode excelRow(JsonNode body, int rowNumber) {
        for (JsonNode row : body.path("rows")) {
            if (row.path("rowNumber").asInt() == rowNumber) {
                return row;
            }
        }
        throw new AssertionError("No row " + rowNumber + " in " + body);
    }

    private JsonNode expectOk(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("validRows").asInt() + body.path("invalidRows").asInt())
                .isEqualTo(body.path("totalRows").asInt());
        return body;
    }

    private Map<String, List<Map<String, Object>>> snapshot() {
        Map<String, List<Map<String, Object>>> state = new LinkedHashMap<>();
        STATE_TABLES.forEach(table -> state.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY 1, 2")));
        return state;
    }

    private void department(String code, boolean active) {
        jdbc.update("INSERT INTO departments (id, code, name, manager_user_id, active, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), code, "Phòng " + code, adminId, active, Timestamp.from(START));
    }

    private UUID sessionOf(UUID userId) {
        return jdbc.queryForObject("SELECT id FROM auth_sessions WHERE user_id = ?", UUID.class, userId);
    }

    private static Jwt jwt(UUID subject, UUID session, Instant expiresAt) {
        return Jwt.withTokenValue("service-test").header("alg", "HS256")
                .subject(subject.toString()).claim("jti", session.toString())
                .issuedAt(START.minusSeconds(1)).expiresAt(expiresAt).build();
    }

    // Row 1 gets the template header; each array is the next row. A null array leaves that row empty.
    private static byte[] staffFile(Object[]... rows) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Nhân sự");
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

    // Values start at column A. null leaves the cell out; a number gets a numeric cell, as if typed into Excel.
    private static void write(Row row, Object... values) {
        for (int column = 0; column < values.length; column++) {
            Object value = values[column];
            if (value == null) {
                continue;
            }
            Cell cell = row.createCell(column);
            if (value instanceof Number number) {
                cell.setCellValue(number.doubleValue());
            } else {
                cell.setCellValue(value.toString());
            }
        }
    }

    private static Object[] row(Object... values) {
        return values;
    }

    private HttpResponse<String> upload(String token, byte[] content) throws Exception {
        String boundary = "ttcs-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"nhan-su.xlsx\"\r\n"
                + "Content-Type: " + XLSX + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(content);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        var request = HttpRequest.newBuilder(uri(PREVIEW)).timeout(Duration.ofSeconds(30))
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
}
