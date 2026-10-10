package vn.ttcs.recruitment.auth;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
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

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who may use the staff import (TKNHTTDNB1-175). Only account administrators, that is the ADMIN role together with
 * USER_ADMIN_WRITE_ALL, may download the template, preview a file or import it. Everyone else is refused with 403
 * before the file is read, so no account is created and no invitation email is sent. The service checks the right
 * again after locking the caller's account and session, so a right lost while a request waits is respected.
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
class StaffImportAccessIntegrationTest {
    private static final String TEMPLATE = "/api/v1/accounts/import/template";
    private static final String PREVIEW = "/api/v1/accounts/import/preview";
    private static final String IMPORT = "/api/v1/accounts/import";
    private static final List<String> IMPORT_APIS = List.of(TEMPLATE, PREVIEW, IMPORT);
    private static final String PASSWORD = "TestingOnly123!";
    private static final String ADMIN_EMAIL = "admin@example.test";
    // The one person in the uploaded file. A valid row: an administrator's import would create this account.
    private static final String NEW_PERSON = "nguoi.moi@example.com";
    private static final Instant START = Instant.parse("2026-10-07T00:00:00Z");
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final List<String> HEADERS = List.of(
            "Email", "Họ và tên", "Vai trò", "Mã phòng ban", "Số điện thoại", "Chức danh hiển thị");
    private static final LocalSmtpServer SMTP = startSmtp();

    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private AccountRepository accounts;
    @Autowired private BootstrapAdmin bootstrap;
    @Autowired private AuthIntegrationTest.MutableClock clock;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private List<Map<String, Object>> permissionSeed;
    private UUID adminId;
    private String adminToken;
    private String fixturePasswordHash;
    private byte[] file;

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
        file = staffFile(NEW_PERSON, "Người Mới", "RECRUITER");
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

    @ParameterizedTest
    @EnumSource(value = Role.class, names = "ADMIN", mode = EnumSource.Mode.EXCLUDE)
    void everyOtherInternalRoleIsRefusedByAllThreeImportApisAndNothingIsCreatedOrSent(Role role) throws Exception {
        String email = role.name().toLowerCase(Locale.ROOT) + "@example.test";
        account(email, Set.of(role));
        String token = login(email).path("accessToken").asText();

        for (String path : IMPORT_APIS) {
            assertError(call(path, token), 403, "FORBIDDEN");
        }
        assertNothingCreatedOrSent();

        // The same file is accepted from the administrator, so the 403 above came from the caller, not the file.
        assertThat(expectOk(call(PREVIEW, adminToken)).path("validRows").asInt()).isEqualTo(1);
    }

    @Test
    void hrManagersReadAccountsButOnlyAdminsWithUserAdminWriteCanImport() throws Exception {
        account("hr@example.test", Set.of(Role.HR_MANAGER));
        String hrToken = login("hr@example.test").path("accessToken").asText();
        // An HR manager administers accounts read-only: the account list is open to them ...
        expectOk(get("/api/v1/accounts", hrToken));
        // ... but importing creates accounts, so none of the three import APIs is.
        for (String path : IMPORT_APIS) {
            assertError(call(path, hrToken), 403, "FORBIDDEN");
        }

        // Roles add up, but no role other than ADMIN gives the right, so having all five of them does not help.
        account("all.roles@example.test", EnumSet.complementOf(EnumSet.of(Role.ADMIN)));
        String allRolesToken = login("all.roles@example.test").path("accessToken").asText();
        for (String path : IMPORT_APIS) {
            assertError(call(path, allRolesToken), 403, "FORBIDDEN");
        }

        // As for POST /accounts, USER_ADMIN_WRITE_ALL granted to HR managers is not enough without the ADMIN role ...
        jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('HR_MANAGER', 'USER_ADMIN_WRITE_ALL')");
        for (String path : IMPORT_APIS) {
            assertError(call(path, hrToken), 403, "FORBIDDEN");
        }
        // ... and the ADMIN role is not enough without USER_ADMIN_WRITE_ALL, even with a token issued before.
        jdbc.update("DELETE FROM role_permissions WHERE role_code = 'ADMIN' AND permission_code = 'USER_ADMIN_WRITE_ALL'");
        for (String path : IMPORT_APIS) {
            assertError(call(path, adminToken), 403, "FORBIDDEN");
        }
        assertNothingCreatedOrSent();
    }

    // Every import API with every change that can take the right away while it is being saved.
    static Stream<Arguments> changesSavedWhileTheRequestWaits() {
        return IMPORT_APIS.stream().flatMap(path -> Stream.of(
                Arguments.of(path, "lost-permission", 403, "FORBIDDEN"),
                Arguments.of(path, "lost-admin-role", 403, "FORBIDDEN"),
                Arguments.of(path, "locked-account", 401, "SESSION_INVALID"),
                Arguments.of(path, "logged-out", 401, "SESSION_INVALID")));
    }

    @ParameterizedTest(name = "{0} after {1}")
    @MethodSource("changesSavedWhileTheRequestWaits")
    void aRightLostWhileTheRequestWaitsForTheCallersLockIsRespected(String path, String change, int status, String code)
            throws Exception {
        assertError(callWhileTheAdminIsLocked(path, change), status, code);
        assertNothingCreatedOrSent();
    }

    @ParameterizedTest
    @ValueSource(strings = {TEMPLATE, PREVIEW, IMPORT})
    void aRequestThatWaitedForTheCallersLockGoesOnWhenTheRightIsKept(String path) throws Exception {
        HttpResponse<byte[]> response = callWhileTheAdminIsLocked(path, "unchanged");
        assertThat(response.statusCode()).as(text(response)).isEqualTo(200);
        // Only the real import creates the account and sends its invitation.
        boolean imported = path.equals(IMPORT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_accounts WHERE email = ?", Integer.class, NEW_PERSON))
                .isEqualTo(imported ? 1 : 0);
        assertThat(SMTP.messages()).hasSize(imported ? 1 : 0);
    }

    @Test
    void anImportWithoutAnyValidRowIsCheckedBeforeTheFileIsReadSoItRevealsNoEmail() throws Exception {
        // The only row uses an email that already has an account, so the import creates nothing and the check that
        // account creation makes for every row never runs. Its report would still tell that this email is taken,
        // so the right must also be checked, after the wait, before the file is read.
        account("existing@example.test", Set.of(Role.RECRUITER));
        file = staffFile("existing@example.test", "Existing", "RECRUITER");

        HttpResponse<byte[]> response = callWhileTheAdminIsLocked(IMPORT, "lost-permission");

        assertError(response, 403, "FORBIDDEN");
        assertThat(text(response)).doesNotContain("existing@example.test").doesNotContain("EMAIL_ALREADY_EXISTS");
        assertNothingCreatedOrSent();
    }

    // Sends the request while another transaction holds the administrator's account row, as a role change, account
    // lock or logout does while it is being saved. Once the request waits for that row in the service (it has
    // passed the security filter by then), the change is saved and committed, and the request goes on.
    private HttpResponse<byte[]> callWhileTheAdminIsLocked(String path, String change) throws Exception {
        // A second administrator, outside the request, who can lock the first one.
        UUID lockOwner = account("owner@example.test", Set.of(Role.ADMIN));
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blockerPid = lockAccount(connection, adminId);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(() -> call(path, adminToken));
                try {
                    awaitWaiters(blockerPid);
                    switch (change) {
                        case "unchanged" -> { }
                        case "lost-permission" -> execute(connection, "DELETE FROM role_permissions "
                                + "WHERE role_code = 'ADMIN' AND permission_code = 'USER_ADMIN_WRITE_ALL'");
                        case "lost-admin-role" -> execute(connection,
                                "DELETE FROM user_roles WHERE user_id = ? AND role = 'ADMIN'", adminId);
                        case "locked-account" -> execute(connection, "UPDATE user_accounts SET admin_locked_at = ?, "
                                + "admin_lock_reason = 'Review', admin_locked_by = ? WHERE id = ?",
                                Timestamp.from(START), lockOwner, adminId);
                        case "logged-out" -> execute(connection,
                                "UPDATE auth_sessions SET revoked_at = ? WHERE user_id = ?", Timestamp.from(START), adminId);
                        default -> throw new IllegalArgumentException(change);
                    }
                    connection.commit();
                    return response.get(30, TimeUnit.SECONDS);
                } finally {
                    connection.rollback();
                }
            }
        }
    }

    // No account for the person in the file, no activation token and no attempt to send an invitation.
    private void assertNothingCreatedOrSent() {
        assertThat(jdbc.queryForList("SELECT email FROM user_accounts", String.class)).doesNotContain(NEW_PERSON);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_activation_tokens", Integer.class)).isZero();
        assertThat(SMTP.deliveryAttempts()).isZero();
        assertThat(SMTP.messages()).isEmpty();
    }

    // A refused request gets a JSON error and nothing else: no template attachment and nothing read from the file.
    private void assertError(HttpResponse<byte[]> response, int status, String code) {
        String body = text(response);
        assertThat(response.statusCode()).as(body).isEqualTo(status);
        assertThat(json.readTree(body).path("code").asText()).as(body).isEqualTo(code);
        assertThat(response.headers().firstValue("Content-Disposition")).isEmpty();
        assertThat(body).doesNotContain(NEW_PERSON);
        if (status == 403) {
            assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        }
    }

    private JsonNode expectOk(HttpResponse<byte[]> response) {
        assertThat(response.statusCode()).as(text(response)).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        return json.readTree(text(response));
    }

    private static String text(HttpResponse<byte[]> response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    private UUID account(String email, Set<Role> roles) {
        return accounts.saveAndFlush(new Account(email, "Import test", fixturePasswordHash, roles, START)).getId();
    }

    // The template is downloaded with GET; preview and import upload the same one-row file as "file".
    private HttpResponse<byte[]> call(String path, String token) throws Exception {
        if (path.equals(TEMPLATE)) {
            return get(path, token);
        }
        String boundary = "ttcs-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"nhan-su.xlsx\"\r\n"
                + "Content-Type: " + XLSX + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(file);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        var request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(60))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<byte[]> get(String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + token).GET();
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
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

    // The template header in row 1 and one person in row 2.
    private static byte[] staffFile(String... person) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Nhân sự");
            write(sheet.createRow(0), HEADERS.toArray(String[]::new));
            write(sheet.createRow(1), person);
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static void write(Row row, String... values) {
        for (int column = 0; column < values.length; column++) {
            row.createCell(column).setCellValue(values[column]);
        }
    }

    private int lockAccount(Connection connection, UUID id) throws Exception {
        try (var statement = connection.prepareStatement("SELECT id FROM user_accounts WHERE id = ? FOR UPDATE")) {
            statement.setObject(1, id);
            try (var row = statement.executeQuery()) { assertThat(row.next()).isTrue(); }
        }
        try (var statement = connection.prepareStatement("SELECT pg_backend_pid()");
             var row = statement.executeQuery()) {
            assertThat(row.next()).isTrue();
            return row.getInt(1);
        }
    }

    private void execute(Connection connection, String sql, Object... values) throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) { statement.setObject(index + 1, values[index]); }
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    // Waits until the HTTP request is blocked in PostgreSQL behind the connection with this backend pid.
    private void awaitWaiters(int blockerPid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        int observed = 0;
        while (System.nanoTime() < deadline) {
            observed = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE datname = current_database() AND wait_event_type = 'Lock'
                      AND ? = ANY(pg_blocking_pids(pid))
                    """, Integer.class, blockerPid);
            if (observed >= 1) { return; }
            Thread.sleep(20);
        }
        assertThat(observed).as("The request must wait for the caller's account lock").isGreaterThanOrEqualTo(1);
    }

    private static LocalSmtpServer startSmtp() {
        try {
            return new LocalSmtpServer();
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
