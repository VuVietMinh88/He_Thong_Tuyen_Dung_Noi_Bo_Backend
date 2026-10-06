package vn.ttcs.recruitment.auth;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
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
import vn.ttcs.recruitment.account.importing.StaffImportPreview;
import vn.ttcs.recruitment.account.importing.StaffImportCheckedRow;
import vn.ttcs.recruitment.account.importing.StaffImportService;
import vn.ttcs.recruitment.common.ApiException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StaffImportPreviewIntegrationTest {
    private static final String PREVIEW = "/api/v1/accounts/import/preview";
    private static final String PASSWORD = "TestingOnly123!";
    private static final Instant START = Instant.parse("2026-10-06T00:00:00Z");
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final int TWO_MB = 2 * 1024 * 1024;
    // Row 1 of the template, which every uploaded file must keep exactly.
    private static final List<String> HEADERS = List.of(
            "Email", "Họ và tên", "Vai trò", "Mã phòng ban", "Số điện thoại", "Chức danh hiển thị");
    private static final List<String> STATE_TABLES = List.of(
            "user_accounts", "user_roles", "account_activation_tokens", "departments");

    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccountRepository accounts;
    @Autowired private BootstrapAdmin bootstrap;
    @Autowired private AuthIntegrationTest.MutableClock clock;
    @Autowired private StaffImportService service;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private List<Map<String, Object>> permissionSeed;
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
        permissionSeed = jdbc.queryForList("SELECT role_code, permission_code FROM role_permissions");
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
    }

    @AfterEach
    void restorePermissionSeed() {
        jdbc.update("DELETE FROM role_permissions");
        jdbc.batchUpdate("INSERT INTO role_permissions (role_code, permission_code) VALUES (?, ?)", permissionSeed.stream()
                .map(row -> new Object[] {row.get("role_code"), row.get("permission_code")}).toList());
    }

    @Test
    void adminPreviewsAFilledInTemplateWithExcelRowNumbersAndNormalizedValues() throws Exception {
        jdbc.update("INSERT INTO departments (id, code, name, manager_user_id, active) VALUES (?, 'HR', 'Nhân sự', ?, TRUE)",
                UUID.randomUUID(), adminId);
        byte[] file = filledTemplate(sheet -> {
            write(sheet.createRow(1), "  An.Nguyen@Example.COM ", " Nguyễn Văn An ", "recruiter, INTERVIEWER, recruiter,",
                    " HR ", "+84912345678", " Chuyên viên tuyển dụng ");
            write(sheet.createRow(2), "binh@example.com", "Trần Thị Bình", "HR_MANAGER");
            // Row 4 is left empty and row 5 has only spaces: both are skipped, later rows keep their numbers.
            write(sheet.createRow(4), " ", "", "   ");
            // Wrong values are still returned exactly as read, next to the errors found in them.
            write(sheet.createRow(5), "not-an-email", null, "boss", "KHONG_CO", "12345", null);
        });
        Map<String, List<Map<String, Object>>> before = snapshot();

        var response = upload(adminToken, "danh-sach-nhan-su.xlsx", file);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("totalRows").asInt()).isEqualTo(3);
        assertThat(body.path("validRows").asInt()).isEqualTo(2);
        assertThat(body.path("invalidRows").asInt()).isEqualTo(1);
        assertThat(body.path("rows")).hasSize(3);
        Map<String, Object> first = new LinkedHashMap<>(Map.of("rowNumber", 2, "email", "an.nguyen@example.com",
                "fullName", "Nguyễn Văn An", "roles", List.of("RECRUITER", "INTERVIEWER"), "departmentCode", "HR",
                "phone", "0912345678", "displayTitle", "Chuyên viên tuyển dụng"));
        first.put("valid", true);
        first.put("errors", List.of());
        assertThat(json.readTree(json.writeValueAsString(first))).isEqualTo(body.path("rows").get(0));
        JsonNode second = body.path("rows").get(1);
        assertThat(second.path("rowNumber").asInt()).isEqualTo(3);
        assertThat(second.path("email").asText()).isEqualTo("binh@example.com");
        assertThat(second.path("roles")).extracting(JsonNode::asText).containsExactly("HR_MANAGER");
        // Empty optional cells are null in JSON, not missing and not "".
        assertThat(second.has("departmentCode")).isTrue();
        assertThat(second.path("departmentCode").isNull()).isTrue();
        assertThat(second.path("phone").isNull()).isTrue();
        assertThat(second.path("displayTitle").isNull()).isTrue();
        assertThat(second.path("valid").asBoolean()).isTrue();
        JsonNode wrong = body.path("rows").get(2);
        assertThat(wrong.path("rowNumber").asInt()).isEqualTo(6);
        assertThat(wrong.path("email").asText()).isEqualTo("not-an-email");
        assertThat(wrong.path("fullName").isNull()).isTrue();
        assertThat(wrong.path("roles")).extracting(JsonNode::asText).containsExactly("BOSS");
        assertThat(wrong.path("departmentCode").asText()).isEqualTo("KHONG_CO");
        assertThat(wrong.path("phone").asText()).isEqualTo("12345");
        assertThat(wrong.path("valid").asBoolean()).isFalse();
        assertThat(wrong.path("errors")).extracting(error -> error.path("cell").asText() + " " + error.path("code").asText())
                .containsExactly("A6 EMAIL_INVALID", "B6 REQUIRED", "C6 ROLE_UNKNOWN", "D6 DEPARTMENT_NOT_FOUND",
                        "E6 PHONE_INVALID");

        // Nothing is created: no account, role, activation token (so no invitation e-mail) or department.
        assertThat(snapshot()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_accounts", Integer.class)).isEqualTo(1);
        // Previewing the same file again gives the same answer.
        assertThat(json.readTree(upload(adminToken, "danh-sach-nhan-su.xlsx", file).body())).isEqualTo(body);
    }

    @Test
    void numericAndBooleanCellsAreReadAsTheTextExcelShows() throws Exception {
        byte[] file = xlsx(workbook -> fill(workbook.createSheet("Nhân sự"),
                row("a@example.com", "A", "RECRUITER", 101, 912345678, true),
                row("b@example.com", "B", "RECRUITER", 12.5, 84912345678L, false)));

        JsonNode rows = rows(expectOk(upload(adminToken, "so.xlsx", file)));

        // A number typed into a General cell shows as 101, never as 101.0. The leading 0 of a phone number
        // typed as a number is already gone in Excel, so the preview reports an invalid phone.
        assertThat(rows.get(0).path("departmentCode").asText()).isEqualTo("101");
        assertThat(rows.get(0).path("phone").asText()).isEqualTo("912345678");
        assertThat(rows.get(0).path("errors")).extracting(error -> error.path("cell").asText() + " " + error.path("code").asText())
                .contains("E2 PHONE_INVALID");
        assertThat(rows.get(0).path("displayTitle").asText()).isEqualTo("TRUE");
        assertThat(rows.get(1).path("departmentCode").asText()).isEqualTo("12.5");
        assertThat(rows.get(1).path("phone").asText()).isEqualTo("84912345678");
        assertThat(rows.get(1).path("displayTitle").asText()).isEqualTo("FALSE");
    }

    @Test
    void onlyTheFirstSheetIsReadAndAFileBetweenOneAndTwoMegabytesIsAccepted() throws Exception {
        Random random = new Random(171);
        byte[] file = xlsx(workbook -> {
            fill(workbook.createSheet("Nhân sự"), row("first@example.com", "First", "RECRUITER"));
            // A large second sheet that looks like staff rows; only the first sheet may be read.
            Sheet other = workbook.createSheet("Dữ liệu khác");
            write(other.createRow(0), HEADERS.toArray());
            for (int index = 1; index <= 4_000; index++) {
                write(other.createRow(index), "other" + index + "@example.com", randomText(random, 400), "ADMIN");
            }
        });
        assertThat(file.length).as("upload size").isBetween(1024 * 1024, TWO_MB);

        JsonNode body = expectOk(upload(adminToken, "lon.xlsx", file));

        assertThat(body.path("totalRows").asInt()).isEqualTo(1);
        assertThat(rows(body).get(0).path("email").asText()).isEqualTo("first@example.com");
    }

    @Test
    void acceptsFiveHundredPeopleAndRejectsTheFiveHundredAndFirst() throws Exception {
        // Blank rows do not count: 500 people spread over 600 rows are still accepted.
        byte[] fiveHundred = xlsx(workbook -> {
            Sheet sheet = workbook.createSheet("Nhân sự");
            fill(sheet);
            int excelRow = 2;
            for (int person = 1; person <= 500; person++) {
                if (person % 5 == 0) {
                    excelRow++;
                }
                write(sheet.createRow(excelRow - 1), "user" + person + "@example.com", "User " + person, "INTERVIEWER");
                excelRow++;
            }
        });
        JsonNode body = expectOk(upload(adminToken, "500.xlsx", fiveHundred));
        assertThat(body.path("totalRows").asInt()).isEqualTo(500);
        // All 500 emails are checked against the existing accounts, and none of them has one yet.
        assertThat(body.path("validRows").asInt()).isEqualTo(500);
        assertThat(rows(body).get(0).path("rowNumber").asInt()).isEqualTo(2);
        assertThat(rows(body).get(499).path("email").asText()).isEqualTo("user500@example.com");
        assertThat(rows(body).get(499).path("rowNumber").asInt()).isEqualTo(601);

        Object[][] people = IntStream.rangeClosed(1, 501)
                .mapToObj(person -> row("user" + person + "@example.com", "User " + person, "INTERVIEWER"))
                .toArray(Object[][]::new);
        assertError(upload(adminToken, "501.xlsx", xlsx(workbook -> fill(workbook.createSheet("Nhân sự"), people))),
                400, "IMPORT_TOO_MANY_ROWS", "500");
    }

    @Test
    void aFileWithoutPeopleIsRejectedAsEmpty() throws Exception {
        assertError(upload(adminToken, "mau.xlsx", downloadTemplate()), 400, "IMPORT_FILE_EMPTY", "dòng 2");
        byte[] onlyBlankRows = xlsx(workbook -> fill(workbook.createSheet("Nhân sự"),
                row("", " "), null, row(null, null, "  ", null, null, "")));
        assertError(upload(adminToken, "trong.xlsx", onlyBlankRows), 400, "IMPORT_FILE_EMPTY", "dòng 2");
    }

    @Test
    void rowOneMustBeExactlyTheTemplateHeader() throws Exception {
        assertHeaderRejected(List.of("Email", "Họ tên", "Vai trò", "Mã phòng ban", "Số điện thoại", "Chức danh hiển thị"),
                "B1", "Họ và tên");
        assertHeaderRejected(List.of("Email", "Vai trò", "Họ và tên", "Mã phòng ban", "Số điện thoại", "Chức danh hiển thị"),
                "B1", "Họ và tên");
        assertHeaderRejected(List.of("email", "Họ và tên", "Vai trò", "Mã phòng ban", "Số điện thoại", "Chức danh hiển thị"),
                "A1", "Email");
        // A key from the documentation is not a header: row 1 keeps the Vietnamese labels.
        assertHeaderRejected(List.of("email", "fullName", "roles", "departmentCode", "phone", "displayTitle"), "A1", "Email");
        assertHeaderRejected(HEADERS.subList(0, 5), "F1", "Chức danh hiển thị");
        List<String> extraColumn = new ArrayList<>(HEADERS);
        extraColumn.add("Ghi chú");
        assertHeaderRejected(extraColumn, "G1", "phải để trống");

        byte[] headerOnRowTwo = xlsx(workbook -> {
            Sheet sheet = workbook.createSheet("Nhân sự");
            write(sheet.createRow(1), HEADERS.toArray());
            write(sheet.createRow(2), "a@example.com", "A", "RECRUITER");
        });
        assertError(upload(adminToken, "dong2.xlsx", headerOnRowTwo), 400, "IMPORT_HEADER_INVALID", "A1");
        byte[] emptySheet = xlsx(workbook -> workbook.createSheet("Nhân sự"));
        assertError(upload(adminToken, "rong.xlsx", emptySheet), 400, "IMPORT_HEADER_INVALID", "A1");

        // The template header is checked on the first sheet only, even when a later sheet has it.
        byte[] templateOnSecondSheet = xlsx(workbook -> {
            workbook.createSheet("Ghi chú");
            fill(workbook.createSheet("Nhân sự"), row("a@example.com", "A", "RECRUITER"));
        });
        assertError(upload(adminToken, "sheet2.xlsx", templateOnSecondSheet), 400, "IMPORT_HEADER_INVALID", "A1");
    }

    @Test
    void headerSpacesAndDecomposedVietnameseAccentsStillMatchTheTemplate() throws Exception {
        List<String> typedDifferently = HEADERS.stream()
                .map(header -> "  " + Normalizer.normalize(header, Normalizer.Form.NFD) + " ").toList();
        assertThat(Normalizer.normalize(HEADERS.get(1), Normalizer.Form.NFD)).isNotEqualTo(HEADERS.get(1));
        byte[] file = xlsx(workbook -> {
            Sheet sheet = workbook.createSheet("Nhân sự");
            write(sheet.createRow(0), typedDifferently.toArray());
            write(sheet.createRow(1), "a@example.com", "A", "RECRUITER");
            // Text to the right of the template columns is not read.
            write(sheet.createRow(2), "b@example.com", "B", "RECRUITER", null, null, null, "ghi chú riêng");
        });

        JsonNode body = expectOk(upload(adminToken, "nfd.xlsx", file));

        assertThat(body.path("totalRows").asInt()).isEqualTo(2);
        assertThat(rows(body).get(1).path("displayTitle").isNull()).isTrue();
    }

    @Test
    void formulasAreRefusedInsteadOfEvaluated() throws Exception {
        byte[] inData = xlsx(workbook -> fill(workbook.createSheet("Nhân sự"),
                row("a@example.com", "A", "RECRUITER"),
                row("b@example.com", "B", "RECRUITER", new Formula("LOWER(\"HR\")"))));
        assertError(upload(adminToken, "congthuc.xlsx", inData), 400, "IMPORT_FORMULA_NOT_ALLOWED", "D3");

        byte[] inHeader = xlsx(workbook -> {
            Sheet sheet = workbook.createSheet("Nhân sự");
            fill(sheet, row("a@example.com", "A", "RECRUITER"));
            sheet.getRow(0).getCell(0).setCellFormula("\"Email\"");
        });
        assertError(upload(adminToken, "congthuc-tieude.xlsx", inHeader), 400, "IMPORT_FORMULA_NOT_ALLOWED", "A1");

        // Columns after the template columns are not read, so a formula there does not matter.
        byte[] outside = xlsx(workbook -> fill(workbook.createSheet("Nhân sự"),
                row("a@example.com", "A", "RECRUITER", null, null, null, null, new Formula("1+1"))));
        assertThat(expectOk(upload(adminToken, "ngoai.xlsx", outside)).path("totalRows").asInt()).isEqualTo(1);
    }

    @Test
    void onlyReadableXlsxFilesAreAccepted() throws Exception {
        byte[] valid = staffFile(row("a@example.com", "A", "RECRUITER"));
        assertThat(expectOk(upload(adminToken, "DANH-SACH.XLSX", valid)).path("totalRows").asInt()).isEqualTo(1);

        for (String name : List.of("danh-sach.xls", "danh-sach.csv", "danh-sach.xlsx.txt", "danh-sach")) {
            assertError(upload(adminToken, name, valid), 400, "IMPORT_FILE_INVALID", ".xlsx");
        }
        byte[] csv = "Email,Họ và tên,Vai trò\na@example.com,A,RECRUITER\n".getBytes(StandardCharsets.UTF_8);
        assertError(upload(adminToken, "csv-doi-ten.xlsx", csv), 400, "IMPORT_FILE_INVALID", ".xlsx");
        byte[] oldExcel;
        try (HSSFWorkbook workbook = new HSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            fill(workbook.createSheet("Nhân sự"), row("a@example.com", "A", "RECRUITER"));
            workbook.write(output);
            oldExcel = output.toByteArray();
        }
        assertError(upload(adminToken, "excel-cu.xlsx", oldExcel), 400, "IMPORT_FILE_INVALID", ".xlsx");
        assertError(upload(adminToken, "zip-thuong.xlsx", zip(Map.of("ghi-chu.txt", "xin chào".getBytes(StandardCharsets.UTF_8)))),
                400, "IMPORT_FILE_INVALID", ".xlsx");
        assertError(upload(adminToken, "hong.xlsx", Arrays.copyOf(valid, valid.length / 2)), 400, "IMPORT_FILE_INVALID", ".xlsx");
    }

    @Test
    void aSmallUploadThatUnzipsToMoreThanTenMegabytesIsRefusedBeforeItIsLoaded() throws Exception {
        byte[] valid = staffFile(row("a@example.com", "A", "RECRUITER"));
        Map<String, byte[]> entries = unzip(valid);
        entries.put("xl/padding.xml", new byte[11 * 1024 * 1024]);
        byte[] bomb = zip(entries);
        assertThat(bomb.length).as("compressed size").isLessThan(200 * 1024);

        assertError(upload(adminToken, "bom.xlsx", bomb), 400, "IMPORT_FILE_INVALID", "giải nén");

        // The same workbook unzipping to a little under the limit is read normally. Two random letters
        // compress about 8 to 1, so POI's own inflate-ratio check does not refuse this file either.
        Random random = new Random(9);
        byte[] padding = new byte[9 * 1024 * 1024];
        for (int index = 0; index < padding.length; index++) {
            padding[index] = (byte) (random.nextBoolean() ? 'a' : 'b');
        }
        entries.put("xl/padding.xml", padding);
        byte[] nearLimit = zip(entries);
        assertThat(nearLimit.length).as("compressed size").isLessThan(TWO_MB);
        assertThat(rows(expectOk(upload(adminToken, "gan-gioi-han.xlsx", nearLimit))).get(0).path("email").asText())
                .isEqualTo("a@example.com");
    }

    @Test
    void uploadsAboveTwoMegabytesAreRejected() throws Exception {
        byte[] random = new byte[TWO_MB + 1024];
        new Random(2).nextBytes(random);
        var tooLarge = upload(adminToken, "qua-lon.xlsx", random);
        assertError(tooLarge, 413, "FILE_TOO_LARGE", "dung lượng");

        // Exactly 2 MB passes the size limit and then fails only because it is not a workbook.
        assertError(upload(adminToken, "dung-2mb.xlsx", Arrays.copyOf(random, TWO_MB)), 400, "IMPORT_FILE_INVALID", ".xlsx");

        // The service keeps the 2 MB promise even if the multipart limit is raised later.
        UUID session = sessionOf(adminId);
        Jwt admin = jwt(adminId, session, START.plus(TokenService.ACCESS_TOKEN_TTL));
        var oversized = new MockMultipartFile("file", "qua-lon.xlsx", XLSX, new byte[TWO_MB + 1]);
        assertThatThrownBy(() -> service.preview(admin, oversized)).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.getStatus()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
            assertThat(error.getCode()).isEqualTo("FILE_TOO_LARGE");
            assertThat(error.getMessage()).contains("2 MB");
        });
    }

    @Test
    void aFileFarAboveTheRequestLimitStillGetsTheJsonErrorInsteadOfADroppedConnection() throws Exception {
        // 5 MB is above max-request-size (3 MB), so the server refuses it before reading any of it. The
        // unread rest is more than Tomcat's default max-swallow-size (2 MB), which used to close the
        // connection before the client could read the 413 answer.
        byte[] random = new byte[5 * 1024 * 1024];
        new Random(5).nextBytes(random);

        assertError(upload(adminToken, "rat-lon.xlsx", random), 413, "FILE_TOO_LARGE", "dung lượng");
    }

    @Test
    void aMissingOrEmptyFileGetsAClearError() throws Exception {
        assertError(send(adminToken, "application/json", "{}".getBytes(StandardCharsets.UTF_8)),
                400, "IMPORT_FILE_REQUIRED", "file");
        assertError(send(adminToken, null, new byte[0]), 400, "IMPORT_FILE_REQUIRED", "file");
        byte[] valid = staffFile(row("a@example.com", "A", "RECRUITER"));
        assertError(send(adminToken, multipart("upload", "a.xlsx", valid)), 400, "IMPORT_FILE_REQUIRED", "file");
        assertError(upload(adminToken, "rong.xlsx", new byte[0]), 400, "IMPORT_FILE_REQUIRED", "file");
    }

    @Test
    void aBrokenMultipartBodyGetsAJsonErrorInsteadOfAServerError() throws Exception {
        String boundary = "ttcs-broken";
        byte[] cutOff = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.xlsx\"\r\n"
                + "Content-Type: " + XLSX + "\r\n\r\nPK not finished").getBytes(StandardCharsets.UTF_8);

        var response = send(adminToken, new Multipart("multipart/form-data; boundary=" + boundary, cutOff));

        assertError(response, 400, "INVALID_MULTIPART", "tải lên");
    }

    @Test
    void previewNeedsAnActiveAdminSessionThatStillHasUserAdminWrite() throws Exception {
        byte[] file = staffFile(row("new.person@example.com", "New Person", "ADMIN"));
        assertUnauthorized(upload(null, "a.xlsx", file));
        assertUnauthorized(upload("not-a-jwt", "a.xlsx", file));

        account("hr@example.test", Set.of(Role.HR_MANAGER));
        account("recruiter@example.test", Set.of(Role.RECRUITER));
        String hrToken = login("hr@example.test").path("accessToken").asText();
        String recruiterToken = login("recruiter@example.test").path("accessToken").asText();
        Map<String, List<Map<String, Object>>> before = snapshot();
        assertForbidden(upload(recruiterToken, "a.xlsx", file));
        // HR managers can read accounts but cannot create them, so they cannot preview an import either,
        // not even after being granted USER_ADMIN_WRITE_ALL without the ADMIN role.
        assertForbidden(upload(hrToken, "a.xlsx", file));
        jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('HR_MANAGER', 'USER_ADMIN_WRITE_ALL')");
        assertForbidden(upload(hrToken, "a.xlsx", file));

        // Permissions are read from the database on every request, so the same token loses access at once.
        jdbc.update("DELETE FROM role_permissions WHERE role_code = 'ADMIN' AND permission_code = 'USER_ADMIN_WRITE_ALL'");
        assertForbidden(upload(adminToken, "a.xlsx", file));
        assertThat(snapshot()).isEqualTo(before);
        jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('ADMIN', 'USER_ADMIN_WRITE_ALL')");
        assertThat(expectOk(upload(adminToken, "a.xlsx", file)).path("totalRows").asInt()).isEqualTo(1);

        UUID lockOwner = account("owner@example.test", Set.of(Role.ADMIN));
        jdbc.update("UPDATE user_accounts SET admin_locked_at = ?, admin_lock_reason = 'Review', admin_locked_by = ? WHERE id = ?",
                Timestamp.from(START), lockOwner, adminId);
        assertUnauthorized(upload(adminToken, "a.xlsx", file));
    }

    @Test
    void serviceChecksTheRolePermissionTokenAndSessionBeforeLookingAtTheFile() throws Exception {
        UUID adminSession = sessionOf(adminId);
        Jwt admin = jwt(adminId, adminSession, START.plus(TokenService.ACCESS_TOKEN_TTL));
        var file = new MockMultipartFile("file", "a.xlsx", XLSX, staffFile(row("a@example.com", "A", "RECRUITER")));
        StaffImportPreview preview = service.preview(admin, file);
        assertThat(preview.totalRows()).isEqualTo(1);
        assertThat(preview.rows()).containsExactly(
                new StaffImportCheckedRow(2, "a@example.com", "A", List.of("RECRUITER"), null, null, null, true, List.of()));

        // Access is checked first, so a caller without access learns nothing about the file, not even that it is missing.
        UUID hrId = account("hr@example.test", Set.of(Role.HR_MANAGER));
        login("hr@example.test");
        jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('HR_MANAGER', 'USER_ADMIN_WRITE_ALL')");
        Jwt hr = jwt(hrId, sessionOf(hrId), START.plus(TokenService.ACCESS_TOKEN_TTL));
        assertThatThrownBy(() -> service.preview(hr, file)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.preview(hr, null)).isInstanceOf(AccessDeniedException.class);

        jdbc.update("DELETE FROM role_permissions WHERE role_code = 'ADMIN' AND permission_code = 'USER_ADMIN_WRITE_ALL'");
        assertThatThrownBy(() -> service.preview(admin, file)).isInstanceOf(AccessDeniedException.class);
        jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('ADMIN', 'USER_ADMIN_WRITE_ALL')");

        assertThatThrownBy(() -> service.preview(jwt(adminId, adminSession, START), file))
                .isInstanceOf(AuthenticationFailureException.class);
        jdbc.update("UPDATE auth_sessions SET revoked_at = ? WHERE id = ?", Timestamp.from(START), adminSession);
        assertThatThrownBy(() -> service.preview(admin, file)).isInstanceOf(AuthenticationFailureException.class);
    }

    @Test
    void simultaneousPreviewsEachGetTheirOwnRows() throws Exception {
        List<CompletableFuture<HttpResponse<String>>> calls = new ArrayList<>();
        for (int file = 0; file < 8; file++) {
            int number = file;
            Object[][] people = IntStream.range(0, 50 + file)
                    .mapToObj(person -> row("file" + number + ".user" + person + "@example.com", "User " + person, "RECRUITER",
                            null, 900_000_000 + person))
                    .toArray(Object[][]::new);
            Multipart body = multipart("file", "file" + file + ".xlsx", staffFile(people));
            calls.add(client.sendAsync(request(adminToken, body.contentType(), body.content()),
                    HttpResponse.BodyHandlers.ofString()));
        }
        CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new)).get();

        for (int file = 0; file < calls.size(); file++) {
            JsonNode body = expectOk(calls.get(file).get());
            assertThat(body.path("totalRows").asInt()).isEqualTo(50 + file);
            for (int person = 0; person < 50 + file; person++) {
                JsonNode row = rows(body).get(person);
                assertThat(row.path("email").asText()).isEqualTo("file" + file + ".user" + person + "@example.com");
                assertThat(row.path("phone").asText()).isEqualTo(String.valueOf(900_000_000 + person));
            }
        }
    }

    private void assertHeaderRejected(List<String> header, String cell, String expected) throws Exception {
        byte[] file = xlsx(workbook -> {
            Sheet sheet = workbook.createSheet("Nhân sự");
            write(sheet.createRow(0), header.toArray());
            write(sheet.createRow(1), "a@example.com", "A", "RECRUITER");
        });
        JsonNode error = assertError(upload(adminToken, "tieu-de.xlsx", file), 400, "IMPORT_HEADER_INVALID", cell);
        assertThat(error.path("message").asText()).as(header.toString()).contains(expected).contains("tệp mẫu");
    }

    private JsonNode assertError(HttpResponse<String> response, int status, String code, String messagePart) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("code").asText()).as(response.body()).isEqualTo(code);
        assertThat(body.path("message").asText()).contains(messagePart);
        assertThat(body.has("rows")).isFalse();
        return body;
    }

    private void assertForbidden(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(403);
        assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo("FORBIDDEN");
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
    }

    private void assertUnauthorized(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(401);
        assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo("UNAUTHORIZED");
    }

    private JsonNode expectOk(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        return json.readTree(response.body());
    }

    private static JsonNode rows(JsonNode body) {
        return body.path("rows");
    }

    private Map<String, List<Map<String, Object>>> snapshot() {
        Map<String, List<Map<String, Object>>> state = new LinkedHashMap<>();
        STATE_TABLES.forEach(table -> state.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY 1, 2")));
        return state;
    }

    private UUID account(String email, Set<Role> roles) {
        return accounts.saveAndFlush(new Account(email, "Import test", fixturePasswordHash, roles, START)).getId();
    }

    private UUID sessionOf(UUID userId) {
        return jdbc.queryForObject("SELECT id FROM auth_sessions WHERE user_id = ?", UUID.class, userId);
    }

    private static Jwt jwt(UUID subject, UUID session, Instant expiresAt) {
        return Jwt.withTokenValue("service-test").header("alg", "HS256")
                .subject(subject.toString()).claim("jti", session.toString())
                .issuedAt(START.minusSeconds(1)).expiresAt(expiresAt).build();
    }

    // The real template from the API, with rows filled in the way an administrator would fill them in Excel.
    private byte[] filledTemplate(Consumer<Sheet> fillIn) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(downloadTemplate()));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            fillIn.accept(workbook.getSheetAt(0));
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private byte[] downloadTemplate() throws Exception {
        var response = client.send(HttpRequest.newBuilder(uri("/api/v1/accounts/import/template"))
                        .timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + adminToken).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    private static byte[] staffFile(Object[]... rows) throws IOException {
        return xlsx(workbook -> fill(workbook.createSheet("Nhân sự"), rows));
    }

    private static byte[] xlsx(Consumer<XSSFWorkbook> content) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            content.accept(workbook);
            workbook.write(output);
            return output.toByteArray();
        }
    }

    // Row 1 gets the template header; each array is the next row. A null array leaves that row out entirely.
    private static void fill(Sheet sheet, Object[]... rows) {
        write(sheet.createRow(0), HEADERS.toArray());
        for (int index = 0; index < rows.length; index++) {
            if (rows[index] != null) {
                write(sheet.createRow(index + 1), rows[index]);
            }
        }
    }

    // Values start at column A. null leaves the cell out; numbers, booleans and formulas get their own cell types.
    private static void write(Row row, Object... values) {
        for (int column = 0; column < values.length; column++) {
            Object value = values[column];
            if (value == null) {
                continue;
            }
            Cell cell = row.createCell(column);
            switch (value) {
                case Number number -> cell.setCellValue(number.doubleValue());
                case Boolean flag -> cell.setCellValue(flag);
                case Formula formula -> cell.setCellFormula(formula.expression());
                default -> cell.setCellValue(value.toString());
            }
        }
    }

    private static Object[] row(Object... values) {
        return values;
    }

    private static String randomText(Random random, int length) {
        String letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder text = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            text.append(letters.charAt(random.nextInt(letters.length())));
        }
        return text.toString();
    }

    private static Map<String, byte[]> unzip(byte[] archive) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }

    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private HttpResponse<String> upload(String token, String fileName, byte[] content) throws Exception {
        return send(token, multipart("file", fileName, content));
    }

    private HttpResponse<String> send(String token, Multipart body) throws Exception {
        return send(token, body.contentType(), body.content());
    }

    private HttpResponse<String> send(String token, String contentType, byte[] content) throws Exception {
        return client.send(request(token, contentType, content), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest request(String token, String contentType, byte[] content) {
        var builder = HttpRequest.newBuilder(uri(PREVIEW)).timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofByteArray(content));
        if (contentType != null) { builder.header("Content-Type", contentType); }
        if (token != null) { builder.header("Authorization", "Bearer " + token); }
        return builder.build();
    }

    // A multipart/form-data body with one file field, built by hand because java.net.http has no helper for it.
    private static Multipart multipart(String field, String fileName, byte[] content) {
        String boundary = "ttcs-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + field + "\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: " + XLSX + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(content);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return new Multipart("multipart/form-data; boundary=" + boundary, body.toByteArray());
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

    private record Multipart(String contentType, byte[] content) { }

    private record Formula(String expression) { }
}
