package vn.ttcs.recruitment.auth;

import jakarta.mail.internet.MimeMessage;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Importing staff (POST /api/v1/accounts/import): valid rows become pending accounts with an invitation email,
 * invalid rows are skipped, and a row that fails late never undoes the rows created before it.
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
class StaffImportIntegrationTest {
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
    private List<Map<String, Object>> permissionSeed;
    private UUID adminId;
    private String adminToken;
    private String fixturePasswordHash;
    private UUID hrDepartment;

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
        permissionSeed = jdbc.queryForList("SELECT role_code, permission_code FROM role_permissions");
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
        hrDepartment = department("HR", true);
        department("IT", true);
        department("OLD", false);
    }

    @AfterEach
    void restorePermissionSeed() {
        jdbc.update("DELETE FROM role_permissions");
        jdbc.batchUpdate("INSERT INTO role_permissions (role_code, permission_code) VALUES (?, ?)", permissionSeed.stream()
                .map(row -> new Object[] {row.get("role_code"), row.get("permission_code")}).toList());
    }

    @AfterAll
    static void closeSmtp() throws IOException {
        SMTP.close();
    }

    @Test
    void validRowsBecomePendingAccountsWithTheirProfileAndInvalidRowsAreSkipped() throws Exception {
        byte[] file = staffFile(
                row(" An@Example.com ", "Nguyễn Văn An", "recruiter, INTERVIEWER", "HR", "+84912345678",
                        "Chuyên viên tuyển dụng"),
                row("sai-email", "Sai Email", "RECRUITER"),
                row(ADMIN_EMAIL, "Trùng Admin", "ADMIN"),
                null,
                row("binh@example.com", "Trần Thị Bình", "HR_MANAGER"),
                row("cuong@example.com", "Lê Văn Cường", "APPROVER", "OLD"),
                row("dung@example.com", null, "RECRUITER"));
        Map<String, Object> adminBefore = accountRow(ADMIN_EMAIL);

        JsonNode body = expectOk(upload(IMPORT, adminToken, file));

        // Every filled-in row, in file order; the empty row 5 is not a person.
        assertThat(statuses(body)).containsExactly(
                "2 CREATED", "3 SKIPPED", "4 SKIPPED", "6 CREATED", "7 SKIPPED", "8 SKIPPED");
        assertThat(new ArrayList<>(body.propertyNames())).containsExactly(
                "totalRows", "createdCount", "skippedCount", "stoppedAtRow", "created", "skipped");
        assertThat(body.path("stoppedAtRow").isNull()).as("every valid row was tried").isTrue();
        JsonNode first = excelRow(body, 2);
        List<String> fields = new ArrayList<>(first.propertyNames());
        assertThat(fields).containsExactly("rowNumber", "email", "accountId");
        assertThat(first.path("email").asText()).isEqualTo("an@example.com");

        UUID anId = UUID.fromString(first.path("accountId").asText());
        Account an = accounts.findById(anId).orElseThrow();
        assertThat(an.getEmail()).isEqualTo("an@example.com");
        assertThat(an.getFullName()).isEqualTo("Nguyễn Văn An");
        assertThat(an.getRoles()).containsExactlyInAnyOrder(Role.RECRUITER, Role.INTERVIEWER);
        assertThat(an.getDepartmentId()).isEqualTo(hrDepartment);
        assertThat(an.getPhone()).isEqualTo("0912345678");
        assertThat(an.getDisplayTitle()).isEqualTo("Chuyên viên tuyển dụng");
        assertThat(an.isEnabled()).as("waits for activation like POST /accounts").isFalse();
        UUID binhId = UUID.fromString(excelRow(body, 6).path("accountId").asText());
        Account binh = accounts.findById(binhId).orElseThrow();
        assertThat(binh.getRoles()).containsExactly(Role.HR_MANAGER);
        assertThat(binh.getDepartmentId()).isNull();
        assertThat(binh.getPhone()).isNull();
        assertThat(binh.getDisplayTitle()).isNull();

        // Nothing exists for the skipped rows, and the administrator's own account is untouched.
        assertThat(emails()).containsExactlyInAnyOrder(ADMIN_EMAIL, "an@example.com", "binh@example.com");
        assertThat(accountRow(ADMIN_EMAIL)).isEqualTo(adminBefore);
        assertThat(jdbc.queryForList("SELECT user_id FROM account_activation_tokens", UUID.class))
                .containsExactlyInAnyOrder(anId, binhId);
        assertThat(recipients()).containsExactlyInAnyOrder("an@example.com", "binh@example.com");
        String invitation = invitationTo("an@example.com");
        assertThat(invitation).contains("Kích hoạt trong 24 giờ");
        // The response never carries the credentials that only the invited person may see.
        assertThat(body.toString()).doesNotContain(temporaryPassword(invitation), activationToken(invitation));
    }

    @Test
    void rowsAreCheckedAgainWhenImportingInsteadOfTrustingThePreview() throws Exception {
        byte[] file = staffFile(
                row("late@example.com", "Late", "RECRUITER"),
                row("moved@example.com", "Moved", "RECRUITER", "IT"),
                row("ok@example.com", "Ok", "RECRUITER", "HR"));
        JsonNode preview = expectOk(upload(PREVIEW, adminToken, file));
        assertThat(preview.path("validRows").asInt()).isEqualTo(3);

        // After the preview, another administrator creates the same email and the IT department is stopped.
        UUID late = accounts.saveAndFlush(Account.pendingActivation("late@example.com", "Đã có từ trước",
                fixturePasswordHash, Set.of(Role.INTERVIEWER), START)).getId();
        jdbc.update("UPDATE departments SET active = FALSE WHERE code = 'IT'");

        JsonNode body = expectOk(upload(IMPORT, adminToken, file));

        assertThat(statuses(body)).containsExactly("2 SKIPPED", "3 SKIPPED", "4 CREATED");
        // The existing account keeps its name, role and password, and its owner gets no second invitation.
        Account existing = accounts.findById(late).orElseThrow();
        assertThat(existing.getFullName()).isEqualTo("Đã có từ trước");
        assertThat(existing.getRoles()).containsExactly(Role.INTERVIEWER);
        assertThat(existing.getPasswordHash()).isEqualTo(fixturePasswordHash);
        assertThat(emails()).containsExactlyInAnyOrder(ADMIN_EMAIL, "late@example.com", "ok@example.com");
        assertThat(recipients()).containsExactly("ok@example.com");
    }

    @Test
    void aRowThatBecomesInvalidWhileTheImportRunsIsSkippedWithoutUndoingTheOtherRows() throws Exception {
        byte[] file = staffFile(
                row("first@example.com", "First", "RECRUITER"),
                row("taken@example.com", "Taken", "RECRUITER"),
                row("moved@example.com", "Moved", "RECRUITER", "IT"),
                row("last@example.com", "Last", "RECRUITER", "HR"));
        UUID taken;
        HttpResponse<String> response;
        SMTP.pauseReceipt();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var importing = executor.submit(() -> upload(IMPORT, adminToken, file));
            try {
                // The first invitation is being delivered, so every row was already checked and found valid,
                // and the first account is not committed yet.
                assertThat(SMTP.awaitData()).isTrue();
                taken = accounts.saveAndFlush(Account.pendingActivation("taken@example.com", "Someone else",
                        fixturePasswordHash, Set.of(Role.APPROVER), START)).getId();
                jdbc.update("UPDATE departments SET active = FALSE WHERE code = 'IT'");
            } finally {
                SMTP.releaseReceipt();
            }
            response = importing.get(30, TimeUnit.SECONDS);
        }

        JsonNode body = expectOk(response);
        assertThat(statuses(body)).containsExactly("2 CREATED", "3 SKIPPED", "4 SKIPPED", "5 CREATED");
        assertThat(emails()).containsExactlyInAnyOrder(ADMIN_EMAIL, "first@example.com", "taken@example.com",
                "last@example.com");
        assertThat(accounts.findById(taken).orElseThrow().getFullName()).isEqualTo("Someone else");
        UUID first = UUID.fromString(excelRow(body, 2).path("accountId").asText());
        UUID last = UUID.fromString(excelRow(body, 5).path("accountId").asText());
        assertThat(jdbc.queryForList("SELECT user_id FROM account_activation_tokens", UUID.class))
                .containsExactlyInAnyOrder(first, last);
        assertThat(recipients()).containsExactlyInAnyOrder("first@example.com", "last@example.com");
    }

    // A full name of nothing but a Unicode space (U+2003) is as empty as no name: POST /accounts refuses it with 400,
    // so the import skips that row and creates no account, instead of saving an account with an invisible name.
    @Test
    void aNameOfOnlyUnicodeSpacesIsSkippedLikeAnEmptyOne() throws Exception {
        byte[] file = staffFile(
                row("an@example.com", "An", "RECRUITER"),
                row("khong-ten@example.com", "\u2003", "RECRUITER"));

        JsonNode body = expectOk(upload(IMPORT, adminToken, file));

        assertThat(statuses(body)).containsExactly("2 CREATED", "3 SKIPPED");
        assertThat(excelRow(body, 3).path("errors").findValuesAsString("code")).containsExactly("REQUIRED");
        assertThat(excelRow(body, 3).path("errors").findValuesAsString("cell")).containsExactly("B3");
        assertThat(emails()).containsExactlyInAnyOrder(ADMIN_EMAIL, "an@example.com");
    }

    @Test
    void anAddressTheMailServerRefusesSkipsOnlyItsRowAndTheNextRowsAreStillCreated() throws Exception {
        byte[] file = staffFile(
                row("an@example.com", "An", "RECRUITER"),
                row("binh@example.com", "Bình", "RECRUITER", "HR"),
                row("sai-email", "Sai", "RECRUITER"),
                row("cuong@example.com", "Cường", "RECRUITER"));
        SMTP.rejectRecipient("binh@example.com");

        JsonNode body = expectOk(upload(IMPORT, adminToken, file));

        // Row 3 is rolled back like a failed POST /accounts. The mail server still works for other addresses,
        // so the import goes on and creates row 5.
        assertThat(statuses(body)).containsExactly("2 CREATED", "3 SKIPPED", "4 SKIPPED", "5 CREATED");
        assertThat(body.path("stoppedAtRow").isNull()).isTrue();
        assertThat(emails()).containsExactlyInAnyOrder(ADMIN_EMAIL, "an@example.com", "cuong@example.com");
        UUID an = UUID.fromString(excelRow(body, 2).path("accountId").asText());
        UUID cuong = UUID.fromString(excelRow(body, 5).path("accountId").asText());
        assertThat(jdbc.queryForList("SELECT user_id FROM account_activation_tokens", UUID.class))
                .containsExactlyInAnyOrder(an, cuong);
        assertThat(recipients()).containsExactlyInAnyOrder("an@example.com", "cuong@example.com");
        assertNoSmtpDetailsInErrors(body, "550", "Mailbox");
    }

    @Test
    void aMailServerThatStopsWorkingStopsTheImportAndMarksTheValidRowsNotTried() throws Exception {
        byte[] file = staffFile(
                row("an@example.com", "An", "RECRUITER"),
                row("binh@example.com", "Bình", "RECRUITER", "HR"),
                row("sai-email", "Sai", "RECRUITER"),
                row("cuong@example.com", "Cường", "RECRUITER"));
        // The mail server takes the first invitation and then fails every message.
        SMTP.rejectDeliveryAfter(1);

        JsonNode body = expectOk(upload(IMPORT, adminToken, file));

        // Row 2 stays created; row 3 is rolled back like a failed POST /accounts and the import stops there.
        // The invalid row 4 is skipped as always; the valid row 5 is not tried: the server got no third message.
        assertThat(statuses(body)).containsExactly("2 CREATED", "3 SKIPPED", "4 SKIPPED", "5 NOT_ATTEMPTED");
        assertThat(body.path("stoppedAtRow").isInt()).as(body.toString()).isTrue();
        assertThat(body.path("stoppedAtRow").asInt()).isEqualTo(3);
        assertThat(SMTP.deliveryAttempts()).isEqualTo(2);
        assertThat(emails()).containsExactlyInAnyOrder(ADMIN_EMAIL, "an@example.com");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_activation_tokens", Integer.class)).isEqualTo(1);
        assertThat(recipients()).containsExactly("an@example.com");
        assertNoSmtpDetailsInErrors(body, "451", "Temporary");

        // Once email works again, the same file creates only what is still missing.
        String anPassword = jdbc.queryForObject("SELECT password_hash FROM user_accounts WHERE email = 'an@example.com'",
                String.class);
        SMTP.reset();
        JsonNode retry = expectOk(upload(IMPORT, adminToken, file));

        assertThat(statuses(retry)).containsExactly("2 SKIPPED", "3 CREATED", "4 SKIPPED", "5 CREATED");
        assertThat(retry.path("stoppedAtRow").isNull()).isTrue();
        assertThat(emails()).containsExactlyInAnyOrder(ADMIN_EMAIL, "an@example.com", "binh@example.com",
                "cuong@example.com");
        assertThat(jdbc.queryForObject("SELECT password_hash FROM user_accounts WHERE email = 'an@example.com'",
                String.class)).isEqualTo(anPassword);
        assertThat(accounts.findById(UUID.fromString(excelRow(retry, 3).path("accountId").asText())).orElseThrow()
                .getDepartmentId()).isEqualTo(hrDepartment);
        assertThat(recipients()).containsExactlyInAnyOrder("binh@example.com", "cuong@example.com");
    }

    @Test
    void twoAdministratorsImportingTheSameFileTogetherCreateEveryPersonOnce() throws Exception {
        account("second.admin@example.test", Set.of(Role.ADMIN));
        String secondToken = login("second.admin@example.test").path("accessToken").asText();
        List<Object[]> people = new ArrayList<>();
        for (int person = 1; person <= 6; person++) {
            people.add(row("person" + person + "@example.com", "Person " + person, "INTERVIEWER", "HR"));
        }
        byte[] file = staffFile(people.toArray(Object[][]::new));

        List<HttpResponse<String>> responses = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstImport = executor.submit(() -> upload(IMPORT, adminToken, file));
            var secondImport = executor.submit(() -> upload(IMPORT, secondToken, file));
            responses.add(firstImport.get(60, TimeUnit.SECONDS));
            responses.add(secondImport.get(60, TimeUnit.SECONDS));
        }

        JsonNode first = expectOk(responses.get(0));
        JsonNode second = expectOk(responses.get(1));
        assertThat(first.path("createdCount").asInt() + second.path("createdCount").asInt()).isEqualTo(6);
        for (int rowNumber = 2; rowNumber <= 7; rowNumber++) {
            // Whichever import reaches a row first creates it; the other skips it because the email now exists.
            JsonNode inFirst = excelRow(first, rowNumber);
            JsonNode inSecond = excelRow(second, rowNumber);
            assertThat(List.of(inFirst.has("accountId"), inSecond.has("accountId")))
                    .as("row " + rowNumber).containsExactlyInAnyOrder(true, false);
            JsonNode skipped = inFirst.has("accountId") ? inSecond : inFirst;
            assertThat(skipped.path("errors").findValuesAsString("code")).containsExactly("EMAIL_ALREADY_EXISTS");
            assertThat(skipped.path("errors").findValuesAsString("cell")).containsExactly("A" + rowNumber);
        }
        for (int person = 1; person <= 6; person++) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM user_accounts WHERE email = ?", Integer.class,
                    "person" + person + "@example.com")).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_activation_tokens", Integer.class)).isEqualTo(6);
        assertThat(recipients()).hasSize(6).doesNotHaveDuplicates();
    }

    @Test
    void onlyAnActiveAdministratorWithUserAdminWriteCanImport() throws Exception {
        byte[] file = staffFile(row("new.person@example.com", "New Person", "ADMIN"));
        assertError(upload(IMPORT, null, file), 401, "UNAUTHORIZED");
        assertError(upload(IMPORT, "not-a-jwt", file), 401, "UNAUTHORIZED");
        for (Role role : Role.values()) {
            if (role == Role.ADMIN) {
                continue;
            }
            String email = role.name().toLowerCase(Locale.ROOT) + "@example.test";
            account(email, Set.of(role));
            assertError(upload(IMPORT, login(email).path("accessToken").asText(), file), 403, "FORBIDDEN");
        }
        // Like POST /accounts, the permission alone is not enough without the ADMIN role ...
        jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('HR_MANAGER', 'USER_ADMIN_WRITE_ALL')");
        assertError(upload(IMPORT, login("hr_manager@example.test").path("accessToken").asText(), file),
                403, "FORBIDDEN");
        // ... and the ADMIN role alone is not enough without the permission, even with a token issued before.
        jdbc.update("DELETE FROM role_permissions WHERE role_code = 'ADMIN' AND permission_code = 'USER_ADMIN_WRITE_ALL'");
        assertError(upload(IMPORT, adminToken, file), 403, "FORBIDDEN");

        assertThat(emails()).doesNotContain("new.person@example.com");
        assertThat(recipients()).isEmpty();
    }

    @Test
    void permissionIsCheckedAgainForEveryRowSoALostPermissionStopsTheImport() throws Exception {
        byte[] file = staffFile(
                row("first@example.com", "First", "RECRUITER"),
                row("second@example.com", "Second", "RECRUITER"));
        HttpResponse<String> response;
        SMTP.pauseReceipt();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var importing = executor.submit(() -> upload(IMPORT, adminToken, file));
            try {
                // The first account is being created when the administrator loses the permission.
                assertThat(SMTP.awaitData()).isTrue();
                jdbc.update("DELETE FROM role_permissions WHERE role_code = 'ADMIN' AND permission_code = 'USER_ADMIN_WRITE_ALL'");
            } finally {
                SMTP.releaseReceipt();
            }
            response = importing.get(30, TimeUnit.SECONDS);
        }

        assertError(response, 403, "FORBIDDEN");
        // The first account was finished before the change and stays; the second one is never created.
        assertThat(emails()).containsExactlyInAnyOrder(ADMIN_EMAIL, "first@example.com");
        assertThat(recipients()).containsExactly("first@example.com");
    }

    @Test
    void aFileThatCannotBeReadCreatesNothing() throws Exception {
        assertError(request(IMPORT, adminToken, "application/json", "{}".getBytes(StandardCharsets.UTF_8)),
                400, "IMPORT_FILE_REQUIRED");
        byte[] wrongHeader = xlsx(sheet -> {
            write(sheet.createRow(0), "Email", "Tên", "Vai trò", "Mã phòng ban", "Số điện thoại", "Chức danh hiển thị");
            write(sheet.createRow(1), "an@example.com", "An", "RECRUITER");
        });
        assertError(upload(IMPORT, adminToken, wrongHeader), 400, "IMPORT_HEADER_INVALID");
        byte[] formula = xlsx(sheet -> {
            write(sheet.createRow(0), HEADERS.toArray());
            write(sheet.createRow(1), "an@example.com", "An", "RECRUITER");
            sheet.createRow(2).createCell(0).setCellFormula("\"binh\"&\"@example.com\"");
        });
        assertError(upload(IMPORT, adminToken, formula), 400, "IMPORT_FORMULA_NOT_ALLOWED");

        // A file is accepted or refused as a whole: the valid row 2 of the refused files was not created either.
        assertThat(emails()).containsExactly(ADMIN_EMAIL);
        assertThat(recipients()).isEmpty();
    }

    // The SMTP server's reply must not reach the client. Only the error code and message are checked: the response
    // also holds generated account UUIDs, which can contain a number such as 550 or 451 by chance.
    private static void assertNoSmtpDetailsInErrors(JsonNode body, String statusCode, String detail) {
        for (JsonNode row : body.path("skipped")) {
            for (JsonNode error : row.path("errors")) {
                assertThat(error.path("code").asText()).doesNotContain(statusCode, detail);
                assertThat(error.path("message").asText()).doesNotContain(statusCode, detail);
            }
        }
    }

    // "2 CREATED" for each row of the report, in Excel row order: CREATED for a row of "created", NOT_ATTEMPTED for
    // a skipped row the import did not try because it had stopped, SKIPPED for every other skipped row.
    // Only a created row has an account id, and no row is in both lists.
    private static List<String> statuses(JsonNode body) {
        Map<Integer, String> statuses = new TreeMap<>();
        for (JsonNode row : body.path("created")) {
            assertThat(row.path("accountId").isString()).as(row.toString()).isTrue();
            assertThat(statuses.put(row.path("rowNumber").asInt(), "CREATED")).as(row.toString()).isNull();
        }
        for (JsonNode row : body.path("skipped")) {
            assertThat(row.has("accountId")).as(row.toString()).isFalse();
            String status = row.path("errors").path(0).path("code").asText().equals("NOT_ATTEMPTED")
                    ? "NOT_ATTEMPTED" : "SKIPPED";
            assertThat(statuses.put(row.path("rowNumber").asInt(), status)).as(row.toString()).isNull();
        }
        return statuses.entrySet().stream().map(entry -> entry.getKey() + " " + entry.getValue()).toList();
    }

    // The row with this Excel row number, from "created" or "skipped".
    private static JsonNode excelRow(JsonNode body, int rowNumber) {
        for (String list : List.of("created", "skipped")) {
            for (JsonNode row : body.path(list)) {
                if (row.path("rowNumber").asInt() == rowNumber) {
                    return row;
                }
            }
        }
        throw new AssertionError("No row " + rowNumber + " in " + body);
    }

    private JsonNode expectOk(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        return json.readTree(response.body());
    }

    private void assertError(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("code").asText()).as(response.body()).isEqualTo(code);
        assertThat(body.has("created")).isFalse();
        assertThat(body.has("skipped")).isFalse();
    }

    private List<String> emails() {
        return jdbc.queryForList("SELECT email FROM user_accounts", String.class);
    }

    private Map<String, Object> accountRow(String email) {
        return jdbc.queryForMap("SELECT * FROM user_accounts WHERE email = ?", email);
    }

    private static List<String> recipients() throws Exception {
        List<String> recipients = new ArrayList<>();
        for (MimeMessage message : SMTP.messages()) {
            recipients.add(message.getAllRecipients()[0].toString());
        }
        return recipients;
    }

    private static String invitationTo(String email) throws Exception {
        for (MimeMessage message : SMTP.messages()) {
            if (message.getAllRecipients()[0].toString().equals(email)) {
                return message.getContent().toString();
            }
        }
        throw new AssertionError("No invitation to " + email);
    }

    private static String activationToken(String content) {
        var matcher = Pattern.compile("http://localhost:5173/activate-account\\?token=([A-Za-z0-9_-]{43})").matcher(content);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private static String temporaryPassword(String content) {
        var matcher = Pattern.compile("Mật khẩu tạm: ([^\\r\\n]+)").matcher(content);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private UUID account(String email, Set<Role> roles) {
        return accounts.saveAndFlush(new Account(email, "Import test", fixturePasswordHash, roles, START)).getId();
    }

    private UUID department(String code, boolean active) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO departments (id, code, name, manager_user_id, active, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                id, code, "Phòng " + code, adminId, active, Timestamp.from(START));
        return id;
    }

    // Row 1 gets the template header; each array is the next row. A null array leaves that row empty.
    private static byte[] staffFile(Object[]... rows) throws IOException {
        return xlsx(sheet -> {
            write(sheet.createRow(0), HEADERS.toArray());
            for (int index = 0; index < rows.length; index++) {
                if (rows[index] != null) {
                    write(sheet.createRow(index + 1), rows[index]);
                }
            }
        });
    }

    private static byte[] xlsx(Consumer<Sheet> fillIn) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            fillIn.accept(workbook.createSheet("Nhân sự"));
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
        return request(path, token, "multipart/form-data; boundary=" + boundary, body.toByteArray());
    }

    private HttpResponse<String> request(String path, String token, String contentType, byte[] content) throws Exception {
        var request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(60))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofByteArray(content));
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
