package vn.ttcs.recruitment.auth;

import jakarta.validation.Validator;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.PaneInformation;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.ContentDisposition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.account.AccountUpdateRequest;
import vn.ttcs.recruitment.account.BootstrapAdmin;
import vn.ttcs.recruitment.account.CreateAccountRequest;
import vn.ttcs.recruitment.account.ProfileValidation;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.account.importing.StaffImportColumn;
import vn.ttcs.recruitment.account.importing.StaffImportService;

import java.io.ByteArrayInputStream;
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
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StaffImportTemplateIntegrationTest {
    private static final String TEMPLATE = "/api/v1/accounts/import/template";
    private static final String PASSWORD = "TestingOnly123!";
    private static final Instant START = Instant.parse("2026-10-06T00:00:00Z");
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    // The documented file contract. Changing a header or key breaks files administrators already filled in.
    private static final List<String> HEADERS = List.of(
            "Email", "Họ và tên", "Vai trò", "Mã phòng ban", "Số điện thoại", "Chức danh hiển thị");
    private static final List<String> KEYS = List.of("email", "fullName", "roles", "departmentCode", "phone", "displayTitle");
    private static final List<String> COLUMN_TABLE_HEADER = List.of("Cột", "Tiêu đề", "Mã cột", "Bắt buộc", "Quy tắc", "Ví dụ");
    private static final List<String> ROLE_TABLE_HEADER = List.of("Mã vai trò", "Tên vai trò");

    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccountRepository accounts;
    @Autowired private BootstrapAdmin bootstrap;
    @Autowired private AuthIntegrationTest.MutableClock clock;
    @Autowired private StaffImportService service;
    @Autowired private Validator validator;

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
        jdbc.update("UPDATE user_accounts SET admin_locked_at = NULL, admin_lock_reason = NULL, admin_locked_by = NULL");
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
    void adminDownloadsAnXlsxAttachmentWhoseFirstSheetHasOnlyTheHeaderRow() throws Exception {
        var response = download(adminToken);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).isEqualTo(XLSX);
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        ContentDisposition disposition = ContentDisposition.parse(
                response.headers().firstValue("Content-Disposition").orElseThrow());
        assertThat(disposition.isAttachment()).isTrue();
        assertThat(disposition.getFilename()).isEqualTo("mau-nhap-nhan-su.xlsx");

        assertThat(Arrays.stream(StaffImportColumn.values()).map(StaffImportColumn::key).toList())
                .containsExactlyElementsOf(KEYS);
        try (var workbook = open(response.body())) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
            assertThat(workbook.getSheetName(0)).isEqualTo("Nhân sự");
            assertThat(workbook.getSheetName(1)).isEqualTo("Hướng dẫn");
            Sheet data = workbook.getSheetAt(0);
            // No sample person in the data sheet, so nothing can be imported by mistake.
            assertThat(data.getPhysicalNumberOfRows()).isEqualTo(1);
            Row header = data.getRow(0);
            assertThat(texts(header)).containsExactlyElementsOf(HEADERS);
            for (int index = 0; index < HEADERS.size(); index++) {
                short fill = header.getCell(index).getCellStyle().getFillForegroundColor();
                // Email, full name and roles are required (orange); the other columns may stay empty (grey).
                assertThat(fill).as(HEADERS.get(index)).isEqualTo(index < 3
                        ? IndexedColors.LIGHT_ORANGE.getIndex() : IndexedColors.GREY_25_PERCENT.getIndex());
                // Typed values stay text, so 0912345678 keeps its leading zero.
                assertThat(data.getColumnStyle(index).getDataFormatString()).as(HEADERS.get(index)).isEqualTo("@");
            }
            PaneInformation pane = data.getPaneInformation();
            assertThat(pane.isFreezePane()).isTrue();
            assertThat(pane.getHorizontalSplitTopRow()).isEqualTo((short) 1);
            assertThat(pane.getVerticalSplitLeftColumn()).isZero();

            for (Sheet sheet : workbook) {
                for (Row row : sheet) {
                    for (Cell cell : row) {
                        assertThat(cell.getCellType()).as("%s!%s", sheet.getSheetName(), cell.getAddress())
                                .isEqualTo(CellType.STRING);
                    }
                }
            }
        }
    }

    @Test
    void guideExplainsEveryColumnAndListsEveryInternalRoleWithItsCatalogName() throws Exception {
        List<List<String>> guide = guideRows(downloadWorkbook());
        assertThat(guide.getFirst()).containsExactly("Hướng dẫn nhập danh sách nhân sự");
        int rulesStart = guide.indexOf(List.of("Quy định chung")) + 1;
        int rulesEnd = rulesStart;
        while (!guide.get(rulesEnd).isEmpty()) {
            rulesEnd++;
        }
        List<String> rules = guide.subList(rulesStart, rulesEnd).stream().map(List::getFirst).toList();
        assertThat(rules).hasSize(6);
        assertThat(rules.get(0)).contains("sheet đầu tiên");
        assertThat(rules.get(1)).contains("Giữ nguyên dòng tiêu đề");
        assertThat(rules.get(2)).contains("tối đa 500 nhân sự");
        assertThat(rules.get(4)).contains("không dùng công thức").contains(".xlsx").contains("2 MB");

        int columnTable = guide.indexOf(COLUMN_TABLE_HEADER);
        assertThat(columnTable).isPositive();
        List<List<String>> columns = guide.subList(columnTable + 1, columnTable + 1 + HEADERS.size());
        assertThat(columns).extracting(row -> row.subList(0, 4)).containsExactly(
                List.of("A", "Email", "email", "Có"),
                List.of("B", "Họ và tên", "fullName", "Có"),
                List.of("C", "Vai trò", "roles", "Có"),
                List.of("D", "Mã phòng ban", "departmentCode", "Không"),
                List.of("E", "Số điện thoại", "phone", "Không"),
                List.of("F", "Chức danh hiển thị", "displayTitle", "Không"));
        assertThat(columns).allSatisfy(row -> {
            assertThat(row).hasSize(6);
            assertThat(row.get(4)).as("rule of " + row.get(1)).isNotBlank();
            assertThat(row.get(5)).as("example of " + row.get(1)).isNotBlank();
        });
        assertThat(guide.get(columnTable + 1 + HEADERS.size())).as("column table ends after six rows").isEmpty();

        int roleTable = guide.indexOf(ROLE_TABLE_HEADER);
        assertThat(guide.subList(roleTable + 1, guide.size())).containsExactlyElementsOf(expectedRoleRows());
        assertThat(guide.subList(roleTable + 1, guide.size()))
                .contains(List.of("ADMIN", "Quản trị hệ thống"), List.of("RECRUITER", "Nhân viên tuyển dụng"))
                .noneMatch(row -> row.getFirst().equals("CANDIDATE"));
    }

    @Test
    void roleNamesFollowTheRoleCatalogInTheDatabase() throws Exception {
        String original = jdbc.queryForObject("SELECT display_name FROM roles WHERE code = 'RECRUITER'", String.class);
        try {
            jdbc.update("UPDATE roles SET display_name = 'Chuyên viên tuyển dụng' WHERE code = 'RECRUITER'");
            List<List<String>> guide = guideRows(downloadWorkbook());
            assertThat(guide).contains(List.of("RECRUITER", "Chuyên viên tuyển dụng"))
                    .doesNotContain(List.of("RECRUITER", original));
        } finally {
            jdbc.update("UPDATE roles SET display_name = ? WHERE code = 'RECRUITER'", original);
        }
    }

    @Test
    void examplesInTheGuidePassTheRulesOfAccountCreationAndProfileUpdate() throws Exception {
        List<List<String>> guide = guideRows(downloadWorkbook());
        int columnTable = guide.indexOf(COLUMN_TABLE_HEADER);
        Map<String, String> examples = guide.subList(columnTable + 1, columnTable + 1 + HEADERS.size()).stream()
                .collect(Collectors.toMap(row -> row.get(2), row -> row.get(5)));

        Set<Role> roles = Arrays.stream(examples.get("roles").split(","))
                .map(String::trim).map(Role::valueOf).collect(Collectors.toSet());
        assertThat(roles).containsExactlyInAnyOrder(Role.RECRUITER, Role.INTERVIEWER);
        var create = new CreateAccountRequest(examples.get("email"), examples.get("fullName"), roles);
        assertThat(validator.validate(create)).isEmpty();
        assertThat(create.email()).isEqualTo(examples.get("email"));
        var profile = new AccountUpdateRequest(examples.get("fullName"), examples.get("phone"),
                examples.get("displayTitle"), null);
        assertThat(validator.validate(profile)).isEmpty();
        assertThat(examples.get("phone")).matches(ProfileValidation.VIETNAM_PHONE_PATTERN);
        // A department code example in the same style as the codes of the department API.
        assertThat(examples.get("departmentCode")).isEqualTo("HR");
    }

    @Test
    void downloadNeedsAnActiveAdminSessionThatStillHasUserAdminWrite() throws Exception {
        assertUnauthorized(download(null));
        assertUnauthorized(download("not-a-jwt"));

        // HR managers can read the account list but cannot create accounts, so they cannot import either.
        account("hr@example.test", Set.of(Role.HR_MANAGER));
        String hrToken = login("hr@example.test").path("accessToken").asText();
        assertForbidden(download(hrToken));
        jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('HR_MANAGER', 'USER_ADMIN_WRITE_ALL')");
        assertForbidden(download(hrToken));

        // Permissions are read from the database on every request, so the same token loses access at once.
        jdbc.update("DELETE FROM role_permissions WHERE role_code = 'ADMIN' AND permission_code = 'USER_ADMIN_WRITE_ALL'");
        assertForbidden(download(adminToken));
        jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('ADMIN', 'USER_ADMIN_WRITE_ALL')");
        assertThat(download(adminToken).statusCode()).isEqualTo(200);

        UUID lockOwner = account("owner@example.test", Set.of(Role.ADMIN));
        jdbc.update("UPDATE user_accounts SET admin_locked_at = ?, admin_lock_reason = 'Review', admin_locked_by = ? WHERE id = ?",
                Timestamp.from(START), lockOwner, adminId);
        assertUnauthorized(download(adminToken));
    }

    @Test
    void serviceChecksTheRolePermissionTokenAndSessionAgainWhenCalledDirectly() throws Exception {
        UUID adminSession = sessionOf(adminId);
        assertThat(service.createTemplate(jwt(adminId, adminSession, START.plus(TokenService.ACCESS_TOKEN_TTL))))
                .startsWith((byte) 'P', (byte) 'K');

        UUID hrId = account("hr@example.test", Set.of(Role.HR_MANAGER));
        login("hr@example.test");
        jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('HR_MANAGER', 'USER_ADMIN_WRITE_ALL')");
        Jwt hr = jwt(hrId, sessionOf(hrId), START.plus(TokenService.ACCESS_TOKEN_TTL));
        assertThatThrownBy(() -> service.createTemplate(hr)).isInstanceOf(AccessDeniedException.class);

        Jwt admin = jwt(adminId, adminSession, START.plus(TokenService.ACCESS_TOKEN_TTL));
        jdbc.update("DELETE FROM role_permissions WHERE role_code = 'ADMIN' AND permission_code = 'USER_ADMIN_WRITE_ALL'");
        assertThatThrownBy(() -> service.createTemplate(admin)).isInstanceOf(AccessDeniedException.class);
        jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('ADMIN', 'USER_ADMIN_WRITE_ALL')");

        assertThatThrownBy(() -> service.createTemplate(jwt(adminId, adminSession, START)))
                .isInstanceOf(AuthenticationFailureException.class);
        jdbc.update("UPDATE auth_sessions SET revoked_at = ? WHERE id = ?", Timestamp.from(START), adminSession);
        assertThatThrownBy(() -> service.createTemplate(admin)).isInstanceOf(AuthenticationException.class);
    }

    @Test
    void frontendOnAnotherOriginCanReadTheDownloadFileName() throws Exception {
        String origin = "http://localhost:5173";
        var response = client.send(HttpRequest.newBuilder(uri(TEMPLATE)).timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + adminToken).header("Origin", origin).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains(origin);
        assertThat(response.headers().firstValue("Access-Control-Expose-Headers").orElseThrow())
                .containsIgnoringCase("Content-Disposition");
    }

    private List<List<String>> expectedRoleRows() {
        Map<String, String> catalog = new HashMap<>();
        jdbc.queryForList("SELECT code, display_name FROM roles WHERE internal")
                .forEach(row -> catalog.put((String) row.get("code"), (String) row.get("display_name")));
        assertThat(catalog).hasSize(Role.values().length);
        return Arrays.stream(Role.values()).map(role -> List.of(role.name(), catalog.get(role.name()))).toList();
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

    private XSSFWorkbook downloadWorkbook() throws Exception {
        var response = download(adminToken);
        assertThat(response.statusCode()).isEqualTo(200);
        return open(response.body());
    }

    private static XSSFWorkbook open(byte[] content) throws Exception {
        return new XSSFWorkbook(new ByteArrayInputStream(content));
    }

    // Every row of the guide sheet as text; rows left empty on purpose become empty lists.
    private static List<List<String>> guideRows(XSSFWorkbook workbook) throws Exception {
        try (workbook) {
            Sheet guide = workbook.getSheet("Hướng dẫn");
            List<List<String>> rows = new ArrayList<>();
            for (int index = 0; index <= guide.getLastRowNum(); index++) {
                rows.add(texts(guide.getRow(index)));
            }
            return rows;
        }
    }

    private static List<String> texts(Row row) {
        List<String> values = new ArrayList<>();
        if (row != null) {
            row.forEach(cell -> values.add(cell.getStringCellValue()));
        }
        return values;
    }

    private void assertForbidden(HttpResponse<byte[]> response) {
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(body(response).path("code").asText()).isEqualTo("FORBIDDEN");
        assertThat(response.headers().firstValue("Content-Disposition")).isEmpty();
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
    }

    private void assertUnauthorized(HttpResponse<byte[]> response) {
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(body(response).path("code").asText()).isEqualTo("UNAUTHORIZED");
        assertThat(response.headers().firstValue("Content-Disposition")).isEmpty();
    }

    private JsonNode body(HttpResponse<byte[]> response) {
        return json.readTree(new String(response.body(), StandardCharsets.UTF_8));
    }

    private HttpResponse<byte[]> download(String token) throws Exception {
        var builder = HttpRequest.newBuilder(uri(TEMPLATE)).timeout(Duration.ofSeconds(20)).GET();
        if (token != null) { builder.header("Authorization", "Bearer " + token); }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
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
