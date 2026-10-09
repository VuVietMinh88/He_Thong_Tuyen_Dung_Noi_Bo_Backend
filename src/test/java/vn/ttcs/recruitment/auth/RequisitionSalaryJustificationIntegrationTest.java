package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// Task 247: a proposed salary outside the standard band of the position needs a written justification, on both
// POST /api/v1/requisitions (create) and PUT /api/v1/requisitions/{id} (save the draft again). The error says only
// that the proposal is outside the band; it never shows the band to the caller.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RequisitionSalaryJustificationIntegrationTest {
    private static final String BASE = "/api/v1/requisitions";
    private static final String PASSWORD = "TestingOnly123!";
    // Access tokens last 15 minutes from START; no test here moves the clock.
    private static final Instant START = Instant.parse("2026-10-07T00:00:00Z");
    // Standard salary band of the fixture position, in whole VND.
    private static final long BAND_MIN = 15_000_000L;
    private static final long BAND_MAX = 25_000_000L;
    private static final String JUSTIFICATION_CODE = "SALARY_JUSTIFICATION_REQUIRED";
    private static final String JUSTIFICATION_MESSAGE =
            "Dải lương đề xuất nằm ngoài dải lương chuẩn của chức danh, vui lòng nhập giải trình.";
    private static final List<String> RESPONSE_FIELDS = List.of("id", "positionId", "departmentId", "headcount",
            "reason", "proposedSalaryMin", "proposedSalaryMax", "salaryJustification", "neededBy", "jobDescription",
            "candidateRequirements", "status", "createdBy", "createdAt", "updatedAt");

    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private AccountRepository accounts;
    @Autowired private BootstrapAdmin bootstrap;
    @Autowired private AuthIntegrationTest.MutableClock clock;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private String fixturePasswordHash;
    // The department head (HIRING_MANAGER, no SALARY_RANGES_READ_ALL) who manages IT and writes the drafts.
    private UUID headId;
    private String headToken;
    private UUID itId;
    private UUID positionId;

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
        jdbc.update("DELETE FROM recruitment_requisitions");
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("UPDATE user_accounts SET department_id = NULL, admin_locked_at = NULL, admin_lock_reason = NULL, admin_locked_by = NULL");
        jdbc.update("UPDATE departments SET parent_id = NULL");
        jdbc.update("DELETE FROM departments");
        jdbc.update("DELETE FROM positions");
        jdbc.update("DELETE FROM user_accounts");
        bootstrap.run(new DefaultApplicationArguments());
        UUID adminId = UUID.fromString(login("admin@example.test").path("user").path("id").asText());
        fixturePasswordHash = accounts.findById(adminId).orElseThrow().getPasswordHash();
        headId = account("head@example.test", Set.of(Role.HIRING_MANAGER));
        headToken = token("head@example.test");
        UUID salesHeadId = account("sales@example.test", Set.of(Role.HIRING_MANAGER));
        itId = department("IT", headId, true);
        department("SALES", salesHeadId, true);
        positionId = position("DEV_JUNIOR", BAND_MIN, BAND_MAX);
    }

    @Test
    void anyFilledEndOutsideTheBandNeedsAJustificationAndABlankOneDoesNotCount() throws Exception {
        // {proposedSalaryMin, proposedSalaryMax}; null means the manager left that end empty in the draft.
        List<Long[]> outsideTheBand = List.of(
                new Long[] {5_000_000L, 10_000_000L},   // the whole proposal is below the band
                new Long[] {30_000_000L, 40_000_000L},  // the whole proposal is above the band
                new Long[] {10_000_000L, 30_000_000L},  // wider than the band on both sides
                new Long[] {0L, BAND_MAX},              // zero is below the band
                new Long[] {10_000_000L, null},         // only the minimum, below
                new Long[] {30_000_000L, null},         // only the minimum, already above the band
                new Long[] {null, 30_000_000L},         // only the maximum, above
                new Long[] {null, 10_000_000L});        // only the maximum, below
        // A blank justification is stored as null, so it is the same as none at all.
        List<String> missingJustifications = Arrays.asList(null, "", "  \n\t ");
        for (Long[] salaries : outsideTheBand) {
            String proposal = Arrays.toString(salaries);
            justificationRequired(create(proposal(salaries[0], salaries[1]), headToken), proposal + " without the key");
            for (String missing : missingJustifications) {
                Map<String, Object> body = proposal(salaries[0], salaries[1]);
                body.put("salaryJustification", missing);
                justificationRequired(create(body, headToken), proposal + " " + json.writeValueAsString(missing));
            }
        }
        assertThat(count()).isZero();
    }

    @Test
    void bothBandEdgesCountAsInsideAndOneDongPastAnEdgeIsOutside() throws Exception {
        List<Long[]> insideTheBand = List.of(
                new Long[] {BAND_MIN, BAND_MAX},        // exactly the standard band
                new Long[] {BAND_MIN, BAND_MIN},        // a fixed salary on the lower edge
                new Long[] {BAND_MAX, BAND_MAX},        // a fixed salary on the upper edge
                new Long[] {18_000_000L, 22_000_000L},
                new Long[] {BAND_MIN, null},            // one end only: each filled end is compared on its own
                new Long[] {BAND_MAX, null},
                new Long[] {null, BAND_MIN},
                new Long[] {null, BAND_MAX},
                new Long[] {null, null});               // no proposal yet, nothing to justify
        for (Long[] salaries : insideTheBand) {
            JsonNode saved = expect(create(proposal(salaries[0], salaries[1]), headToken), 201,
                    Arrays.toString(salaries));
            assertThat(saved.path("salaryJustification").isNull()).isTrue();
            assertThat(row(id(saved)).get("salary_justification")).isNull();
        }
        assertThat(count()).isEqualTo(insideTheBand.size());

        // One dong below the minimum or above the maximum is already outside.
        justificationRequired(create(proposal(BAND_MIN - 1, BAND_MAX), headToken), "one dong below");
        justificationRequired(create(proposal(BAND_MIN, BAND_MAX + 1), headToken), "one dong above");
        justificationRequired(create(proposal(BAND_MIN - 1, null), headToken), "only the minimum, one dong below");
        justificationRequired(create(proposal(null, BAND_MAX + 1), headToken), "only the maximum, one dong above");

        // A fixed band (salary_min = salary_max) accepts exactly its one amount.
        UUID fixed = position("FIXED_SALARY", 20_000_000L, 20_000_000L);
        Map<String, Object> exact = proposal(20_000_000L, 20_000_000L);
        exact.put("positionId", fixed);
        expect(create(exact, headToken), 201, "fixed band, exact amount");
        Map<String, Object> above = proposal(20_000_000L, 20_000_001L);
        above.put("positionId", fixed);
        justificationRequired(create(above, headToken), "fixed band, one dong above");
        Map<String, Object> below = proposal(19_999_999L, 20_000_000L);
        below.put("positionId", fixed);
        justificationRequired(create(below, headToken), "fixed band, one dong below");
        assertThat(count()).isEqualTo(insideTheBand.size() + 1);
    }

    @Test
    void aJustificationLetsAnOffBandProposalBeSavedAndIsKeptExactlyAsTyped() throws Exception {
        // Leading spaces, a list and a Windows line break are the manager's formatting and are stored as typed.
        String justification = "  Thị trường khan hiếm ứng viên Java.\n- Cần 5 năm kinh nghiệm\r\n";
        Map<String, Object> body = proposal(10_000_000L, 40_000_000L);
        body.put("salaryJustification", justification);
        JsonNode saved = expect(create(body, headToken), 201, "off-band with a justification");
        assertThat(saved.path("proposedSalaryMin").asLong()).isEqualTo(10_000_000L);
        assertThat(saved.path("proposedSalaryMax").asLong()).isEqualTo(40_000_000L);
        assertThat(saved.path("salaryJustification").asText()).isEqualTo(justification);
        Map<String, Object> row = row(id(saved));
        assertThat(row.get("proposed_salary_min")).isEqualTo(10_000_000L);
        assertThat(row.get("proposed_salary_max")).isEqualTo(40_000_000L);
        assertThat(row.get("salary_justification")).isEqualTo(justification);

        // Inside the band a justification is optional; when one is written it is kept, not thrown away.
        Map<String, Object> inside = proposal(BAND_MIN, BAND_MAX);
        inside.put("salaryJustification", "Theo dải lương chuẩn.");
        JsonNode insideSaved = expect(create(inside, headToken), 201, "inside with a justification");
        assertThat(insideSaved.path("salaryJustification").asText()).isEqualTo("Theo dải lương chuẩn.");
        assertThat(row(id(insideSaved)).get("salary_justification")).isEqualTo("Theo dải lương chuẩn.");
        assertThat(count()).isEqualTo(2);
    }

    @Test
    void savingADraftAgainAppliesTheSameRuleAndARefusedSaveChangesNothing() throws Exception {
        UUID draft = id(expect(create(proposal(BAND_MIN, BAND_MAX), headToken), 201, "inside"));
        Map<String, Object> before = row(draft);

        // Raising the maximum above the band without a justification is refused; the stored draft stays as it was.
        justificationRequired(put(draft, proposal(BAND_MIN, 30_000_000L), headToken), "PUT above without");
        assertThat(row(draft)).isEqualTo(before);

        // With a justification the same proposal is saved.
        Map<String, Object> justified = proposal(BAND_MIN, 30_000_000L);
        justified.put("salaryJustification", "Cần người có chứng chỉ AWS.");
        JsonNode updated = expect(put(draft, justified, headToken), 200, "PUT above with");
        assertThat(updated.path("proposedSalaryMax").asLong()).isEqualTo(30_000_000L);
        assertThat(updated.path("salaryJustification").asText()).isEqualTo("Cần người có chứng chỉ AWS.");
        Map<String, Object> saved = row(draft);
        assertThat(saved.get("salary_justification")).isEqualTo("Cần người có chứng chỉ AWS.");

        // PUT replaces every field, so dropping the justification while the proposal is still outside is refused and
        // the stored justification stays.
        Map<String, Object> dropped = proposal(BAND_MIN, 30_000_000L);
        dropped.put("salaryJustification", "   ");
        justificationRequired(put(draft, dropped, headToken), "PUT above, justification removed");
        assertThat(row(draft)).isEqualTo(saved);

        // Back inside the band, the justification may be removed.
        JsonNode inside = expect(put(draft, proposal(BAND_MIN, BAND_MAX), headToken), 200, "PUT back inside");
        assertThat(inside.path("salaryJustification").isNull()).isTrue();
        assertThat(row(draft).get("salary_justification")).isNull();
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void everySaveUsesTheCurrentBandOfThePositionInTheBody() throws Exception {
        Map<String, Object> body = proposal(20_000_000L, BAND_MAX);
        UUID draft = id(expect(create(body, headToken), 201, "inside the first band"));
        Map<String, Object> before = row(draft);

        // HR lowers the maximum of the band after the draft was saved. Reading the draft is not a save: it still works.
        jdbc.update("UPDATE positions SET salary_max = ? WHERE id = ?", 22_000_000L, positionId);
        assertThat(expect(get(draft, headToken), 200, "GET").path("proposedSalaryMax").asLong()).isEqualTo(BAND_MAX);
        // Saving the same content again, or a new draft with it, is compared with the new band.
        justificationRequired(put(draft, body, headToken), "PUT after the band was lowered");
        justificationRequired(create(body, headToken), "POST after the band was lowered");
        assertThat(row(draft)).isEqualTo(before);
        assertThat(count()).isEqualTo(1);
        // When HR raises the band again, the same body is saved without a justification.
        jdbc.update("UPDATE positions SET salary_max = ? WHERE id = ?", BAND_MAX, positionId);
        expect(put(draft, body, headToken), 200, "PUT after the band was raised again");

        // The band of the position chosen in the body counts, not the one the draft used before.
        UUID senior = position("DEV_SENIOR", 30_000_000L, 45_000_000L);
        Map<String, Object> moved = proposal(20_000_000L, BAND_MAX);
        moved.put("positionId", senior);
        justificationRequired(put(draft, moved, headToken), "PUT to the senior position");
        Map<String, Object> seniorBand = proposal(30_000_000L, 45_000_000L);
        seniorBand.put("positionId", senior);
        assertThat(expect(put(draft, seniorBand, headToken), 200, "PUT inside the senior band")
                .path("positionId").asText()).isEqualTo(senior.toString());
    }

    // The rule is the same for every role that may write requisitions, and the answer never contains the band:
    // only HR_MANAGER may see salary bands (SALARY_RANGES_READ_ALL), and even HR_MANAGER reads them on the
    // positions API, not here.
    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"HIRING_MANAGER", "ADMIN", "HR_MANAGER"})
    void theAnswerOnlySaysOutsideTheBandAndNeverShowsTheBand(Role role) throws Exception {
        boolean seesBands = RolePermissionSeedMigrationTest.EXPECTED_GRANTS.get(role.name())
                .contains("SALARY_RANGES_READ_ALL");
        // Written out as well, so this test does not only repeat the seed.
        assertThat(seesBands).isEqualTo(role == Role.HR_MANAGER);
        UUID caller = account("writer@example.test", Set.of(role));
        String token = token("writer@example.test");
        // The caller manages this department, so the department-scope rule of task 249 keeps this result valid.
        UUID managed = department("OWN", caller, true);

        Map<String, Object> body = proposal(BAND_MIN - 1, BAND_MAX + 1);
        body.put("departmentId", managed);
        var refused = create(body, token);
        JsonNode error = justificationRequired(refused, role.name());
        // Only code, message and the one form error, and not a single digit: no amount can leak through the text.
        assertThat(error.size()).isEqualTo(3);
        assertThat(refused.body()).doesNotContainPattern("[0-9]");
        assertThat(count()).isZero();

        body.put("salaryJustification", "Cần chuyên gia có kinh nghiệm dẫn dắt nhóm.");
        JsonNode saved = expect(create(body, token), 201, role.name());
        // Exactly the requisition fields: the caller's own proposal, no salaryMin/salaryMax of the position.
        assertThat(saved.size()).isEqualTo(RESPONSE_FIELDS.size());
        RESPONSE_FIELDS.forEach(field -> assertThat(saved.has(field)).as(field).isTrue());
        assertThat(saved.path("proposedSalaryMin").asLong()).isEqualTo(BAND_MIN - 1);
        assertThat(saved.path("proposedSalaryMax").asLong()).isEqualTo(BAND_MAX + 1);
    }

    @Test
    void theEarlierChecksComeFirstAndCallersWithoutAccessAreForbidden() throws Exception {
        UUID draft = id(expect(create(proposal(BAND_MIN, BAND_MAX), headToken), 201, "inside"));
        Map<String, Object> before = row(draft);
        // Every body below is outside the band and has no justification.

        // A too long justification is a VALIDATION_ERROR about its length, not "justification required".
        Map<String, Object> tooLong = proposal(BAND_MIN - 1, BAND_MAX);
        tooLong.put("salaryJustification", "x".repeat(2_001));
        JsonNode lengthError = expect(create(tooLong, headToken), 400, "too long");
        assertThat(lengthError.path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(lengthError.path("fieldErrors").path("salaryJustification").asText())
                .isEqualTo("Giải trình lương tối đa 2.000 ký tự.");
        // An inverted proposal is reported as inverted first.
        error(create(proposal(BAND_MAX + 1, BAND_MIN - 1), headToken), "REQUISITION_SALARY_RANGE_INVALID");
        // An unknown or closed position is a form error on positionId: a closed position has no standard band, and
        // the 409 POSITION_INACTIVE of SalaryBandService is never reached.
        Map<String, Object> unknownPosition = proposal(BAND_MIN - 1, BAND_MAX);
        unknownPosition.put("positionId", UUID.randomUUID());
        error(create(unknownPosition, headToken), "INVALID_REQUISITION_POSITION");
        UUID closedPosition = position("CLOSED_POSITION", BAND_MIN, BAND_MAX);
        jdbc.update("UPDATE positions SET active = FALSE WHERE id = ?", closedPosition);
        Map<String, Object> inactivePosition = proposal(BAND_MIN - 1, BAND_MAX);
        inactivePosition.put("positionId", closedPosition);
        error(create(inactivePosition, headToken), "REQUISITION_POSITION_INACTIVE");
        error(put(draft, inactivePosition, headToken), "REQUISITION_POSITION_INACTIVE");
        // A closed department is reported before the salary as well.
        Map<String, Object> inactiveDepartment = proposal(BAND_MIN - 1, BAND_MAX);
        inactiveDepartment.put("departmentId", department("CLOSED", headId, false));
        error(create(inactiveDepartment, headToken), "REQUISITION_DEPARTMENT_INACTIVE");

        // INTERVIEWER has no requisition permission, and the SALES head may not change this IT draft: both get the
        // standard 403 before anything is said about the salary.
        account("interviewer@example.test", Set.of(Role.INTERVIEWER));
        forbidden(create(proposal(BAND_MIN - 1, BAND_MAX), token("interviewer@example.test")));
        forbidden(put(draft, proposal(BAND_MIN - 1, BAND_MAX), token("sales@example.test")));
        assertThat(row(draft)).isEqualTo(before);
        assertThat(count()).isEqualTo(1);
    }

    // The request reads the band only after HR's uncommitted change of it is finished: the position row is locked,
    // so the request waits, then compares with the band HR committed, or with the old band when HR rolls back.
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void createWaitsForAnUncommittedBandChangeAndUsesTheResult(boolean commit) throws Exception {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blockerPid = backendPid(connection);
            // 20.000.000–25.000.000 is inside the old band but above the new maximum of 22.000.000.
            assertThat(update(connection, "UPDATE positions SET salary_max = ? WHERE id = ?", 22_000_000L, positionId))
                    .isEqualTo(1);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(() -> create(proposal(20_000_000L, BAND_MAX), headToken));
                try {
                    awaitWaiters(blockerPid, 1);
                    if (commit) { connection.commit(); } else { connection.rollback(); }
                    var result = response.get(10, TimeUnit.SECONDS);
                    if (commit) {
                        justificationRequired(result, "HR committed the lower band");
                    } else {
                        expect(result, 201, "HR rolled back");
                    }
                } finally {
                    connection.rollback();
                }
            }
        }
        assertThat(count()).isEqualTo(commit ? 0 : 1);
    }

    // A valid draft of the fixture position and IT with this salary proposal; null leaves that end empty.
    private Map<String, Object> proposal(Long salaryMin, Long salaryMax) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("positionId", positionId);
        result.put("departmentId", itId);
        result.put("headcount", 2);
        result.put("reason", "NEW_HEADCOUNT");
        result.put("proposedSalaryMin", salaryMin);
        result.put("proposedSalaryMax", salaryMax);
        return result;
    }

    private UUID account(String email, Set<Role> roles) {
        return accounts.saveAndFlush(new Account(email, "Requisition test", fixturePasswordHash, roles, START)).getId();
    }

    private UUID department(String code, UUID manager, boolean active) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO departments (id, code, name, manager_user_id, active, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, code, "Phòng " + code, manager, active, Timestamp.from(START));
        return id;
    }

    private UUID position(String code, long salaryMin, long salaryMax) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO positions (id, code, name, level, salary_min, salary_max, active, created_at, updated_at)
                VALUES (?, ?, 'Lập trình viên', 'Junior', ?, ?, TRUE, ?, ?)
                """, id, code, salaryMin, salaryMax, Timestamp.from(START), Timestamp.from(START));
        return id;
    }

    private int count() {
        return jdbc.queryForObject("SELECT count(*) FROM recruitment_requisitions", Integer.class);
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT * FROM recruitment_requisitions WHERE id = ?", id);
    }

    private static UUID id(JsonNode requisition) {
        return UUID.fromString(requisition.path("id").asText());
    }

    private JsonNode login(String email) throws Exception {
        return expect(request("POST", "/api/v1/auth/login",
                json.writeValueAsString(Map.of("email", email, "password", PASSWORD)), null), 200, "login " + email);
    }

    private String token(String email) throws Exception {
        return login(email).path("accessToken").asText();
    }

    private HttpResponse<String> create(Map<String, Object> body, String token) throws Exception {
        return request("POST", BASE, json.writeValueAsString(body), token);
    }

    private HttpResponse<String> put(UUID id, Map<String, Object> body, String token) throws Exception {
        return request("PUT", BASE + "/" + id, json.writeValueAsString(body), token);
    }

    private HttpResponse<String> get(UUID id, String token) throws Exception {
        return request("GET", BASE + "/" + id, null, token);
    }

    private HttpResponse<String> request(String method, String path, String payload, String token) throws Exception {
        URI uri = URI.create("http://127.0.0.1:" + environment.getRequiredProperty("local.server.port") + path);
        var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                .method(method, payload == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(payload));
        if (token != null) { builder.header("Authorization", "Bearer " + token); }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    // The status first, with the case and the body in the failure message, then the parsed body.
    private JsonNode expect(HttpResponse<String> response, int expected, String description) {
        assertThat(response.statusCode()).as(description + ": " + response.body()).isEqualTo(expected);
        return json.readTree(response.body());
    }

    private void error(HttpResponse<String> response, String code) {
        assertThat(expect(response, 400, code).path("code").asText()).isEqualTo(code);
    }

    // 400 SALARY_JUSTIFICATION_REQUIRED: the same text is the message and the only form error, on salaryJustification.
    private JsonNode justificationRequired(HttpResponse<String> response, String description) {
        JsonNode body = expect(response, 400, description);
        assertThat(body.path("code").asText()).as(description).isEqualTo(JUSTIFICATION_CODE);
        assertThat(body.path("message").asText()).as(description).isEqualTo(JUSTIFICATION_MESSAGE);
        assertThat(body.path("fieldErrors").size()).as(description).isEqualTo(1);
        assertThat(body.path("fieldErrors").path("salaryJustification").asText()).as(description)
                .isEqualTo(JUSTIFICATION_MESSAGE);
        noStore(response);
        return body;
    }

    private void forbidden(HttpResponse<String> response) {
        JsonNode body = expect(response, 403, "forbidden");
        assertThat(body.path("code").asText()).isEqualTo("FORBIDDEN");
        assertThat(body.path("message").asText()).isEqualTo("Bạn không có quyền thực hiện thao tác này.");
        noStore(response);
    }

    private void noStore(HttpResponse<String> response) {
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
    }

    private int backendPid(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT pg_backend_pid()");
             var row = statement.executeQuery()) {
            assertThat(row.next()).isTrue();
            return row.getInt(1);
        }
    }

    private int update(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) { statement.setObject(index + 1, values[index]); }
            return statement.executeUpdate();
        }
    }

    private void awaitWaiters(int blockerPid, int expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        int observed = 0;
        while (System.nanoTime() < deadline) {
            observed = jdbc.queryForObject("""
                    WITH RECURSIVE blocked(pid) AS (
                        SELECT pid FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))
                        UNION
                        SELECT activity.pid FROM pg_stat_activity activity
                        JOIN blocked ON blocked.pid = ANY(pg_blocking_pids(activity.pid))
                    )
                    SELECT count(*) FROM pg_stat_activity activity JOIN blocked ON blocked.pid = activity.pid
                    WHERE activity.datname = current_database() AND activity.wait_event_type = 'Lock'
                    """, Integer.class, blockerPid);
            if (observed >= expected) { return; }
            Thread.sleep(20);
        }
        assertThat(observed).as("HTTP requests must reach PostgreSQL locks before release").isGreaterThanOrEqualTo(expected);
    }
}
