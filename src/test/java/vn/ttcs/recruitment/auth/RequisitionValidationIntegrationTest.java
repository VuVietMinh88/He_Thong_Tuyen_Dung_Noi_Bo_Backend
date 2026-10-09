package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.account.BootstrapAdmin;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.department.DepartmentRepository;
import vn.ttcs.recruitment.position.PositionRepository;

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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Task 246: required fields, the reason code, the headcount range and an active position and department, on both
// POST /api/v1/requisitions (create) and PUT /api/v1/requisitions/{id} (save the draft again).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RequisitionValidationIntegrationTest {
    private static final String BASE = "/api/v1/requisitions";
    private static final String PASSWORD = "TestingOnly123!";
    // Access tokens last 15 minutes from START; no test here moves the clock.
    private static final Instant START = Instant.parse("2026-10-07T00:00:00Z");
    private static final String FORM_MESSAGE = "Vui lòng kiểm tra dữ liệu đã nhập.";
    private static final String REASON_MESSAGE =
            "Lý do tuyển chỉ được là REPLACEMENT (tuyển thay thế) hoặc NEW_HEADCOUNT (tăng mới).";
    private static final String HEADCOUNT_MAX_MESSAGE = "Số lượng cần tuyển tối đa 999 người.";
    private static final String POSITION_INACTIVE_MESSAGE = "Chức danh đã ngừng áp dụng, hãy chọn chức danh khác.";
    private static final String DEPARTMENT_INACTIVE_MESSAGE = "Phòng ban đã ngừng áp dụng, hãy chọn phòng ban khác.";

    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private AccountRepository accounts;
    @Autowired private BootstrapAdmin bootstrap;
    @Autowired private AuthIntegrationTest.MutableClock clock;
    @Autowired private PositionRepository positionRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private String fixturePasswordHash;
    // The department head (HIRING_MANAGER) who manages IT and writes the drafts.
    private UUID headId;
    private String headToken;
    private UUID itId;
    private UUID positionId;
    private UUID otherPositionId;

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
        itId = department("IT", null, headId, true);
        department("SALES", null, salesHeadId, true);
        positionId = position("DEV_JUNIOR");
        otherPositionId = position("QA_SENIOR");
    }

    @Test
    void aReasonOutsideTheTwoCodesIsAClearFieldErrorOnCreateAndUpdate() throws Exception {
        UUID draft = id(expect(create(payload(positionId, itId), headToken), 201));
        Map<String, Object> before = row(draft);
        // Codes are exact and case-sensitive: no other word, no lower case, no spaces around, not both at once.
        List<String> wrongCodes = List.of("PROMOTION", "replacement", "New_Headcount", " REPLACEMENT", "NEW_HEADCOUNT ",
                "", "REPLACEMENT|NEW_HEADCOUNT", "REPLACEMENT,NEW_HEADCOUNT");
        for (String wrong : wrongCodes) {
            Map<String, Object> body = payload(positionId, itId);
            body.put("reason", wrong);
            assertThat(reasonError(create(body, headToken))).as(wrong).isEqualTo(REASON_MESSAGE);
            assertThat(reasonError(put(draft, body, headToken))).as(wrong).isEqualTo(REASON_MESSAGE);
        }
        // A JSON number or true/false is read as its text ("1", "true"), which is not a code either.
        for (String raw : List.of("1", "true")) {
            assertThat(reasonError(request("POST", BASE, withRawField("reason", raw), headToken))).as(raw)
                    .isEqualTo(REASON_MESSAGE);
            assertThat(reasonError(request("PUT", BASE + "/" + draft, withRawField("reason", raw), headToken)))
                    .as(raw).isEqualTo(REASON_MESSAGE);
        }
        // An object or a list is not a text value at all: the JSON itself does not fit the contract.
        for (String raw : List.of("{}", "[\"REPLACEMENT\"]")) {
            error(request("POST", BASE, withRawField("reason", raw), headToken), 400, "INVALID_JSON");
            error(request("PUT", BASE + "/" + draft, withRawField("reason", raw), headToken), 400, "INVALID_JSON");
        }
        assertThat(count()).isEqualTo(1);
        assertThat(row(draft)).isEqualTo(before);

        // Both codes are accepted on create and on update, and stored as the same text.
        Map<String, Object> replacement = payload(positionId, itId);
        replacement.put("reason", "REPLACEMENT");
        JsonNode created = expect(create(replacement, headToken), 201);
        assertThat(created.path("reason").asText()).isEqualTo("REPLACEMENT");
        assertThat(row(id(created)).get("reason")).isEqualTo("REPLACEMENT");
        Map<String, Object> newHeadcount = payload(positionId, itId);
        newHeadcount.put("reason", "NEW_HEADCOUNT");
        assertThat(expect(put(id(created), newHeadcount, headToken), 200).path("reason").asText())
                .isEqualTo("NEW_HEADCOUNT");
        assertThat(row(id(created)).get("reason")).isEqualTo("NEW_HEADCOUNT");
    }

    @Test
    void aWrongReasonIsReportedTogetherWithTheOtherBrokenFields() throws Exception {
        Map<String, Object> body = payload(positionId, itId);
        body.remove("positionId");
        body.put("headcount", 1_000);
        body.put("reason", "OTHER");
        body.put("jobDescription", "x".repeat(10_001));

        JsonNode errors = fieldErrors(create(body, headToken), "positionId", "headcount", "reason", "jobDescription");
        assertThat(errors.path("positionId").asText()).isEqualTo("Chức danh không được để trống.");
        assertThat(errors.path("headcount").asText()).isEqualTo(HEADCOUNT_MAX_MESSAGE);
        assertThat(errors.path("reason").asText()).isEqualTo(REASON_MESSAGE);
        assertThat(errors.path("jobDescription").asText()).isEqualTo("Mô tả công việc tối đa 10.000 ký tự.");
        assertThat(count()).isZero();
    }

    @Test
    void requiredFieldsHaveTheirOwnMessageOnCreateAndUpdate() throws Exception {
        UUID draft = id(expect(create(payload(positionId, itId), headToken), 201));
        Map<String, Object> before = row(draft);
        Map<String, String> messages = new LinkedHashMap<>();
        messages.put("positionId", "Chức danh không được để trống.");
        messages.put("departmentId", "Phòng ban không được để trống.");
        messages.put("headcount", "Số lượng cần tuyển không được để trống.");
        messages.put("reason", "Lý do tuyển không được để trống.");
        for (var required : messages.entrySet()) {
            String field = required.getKey();
            Map<String, Object> missing = payload(positionId, itId);
            missing.remove(field);
            Map<String, Object> nullValue = payload(positionId, itId);
            nullValue.put(field, null);
            for (Map<String, Object> body : List.of(missing, nullValue)) {
                assertThat(fieldErrors(create(body, headToken), field).path(field).asText()).as(field)
                        .isEqualTo(required.getValue());
                assertThat(fieldErrors(put(draft, body, headToken), field).path(field).asText()).as(field)
                        .isEqualTo(required.getValue());
            }
        }
        assertThat(count()).isEqualTo(1);
        assertThat(row(draft)).isEqualTo(before);
    }

    @Test
    void headcountIsAWholeNumberFromOneToNineHundredNinetyNineOnCreateAndUpdate() throws Exception {
        UUID draft = id(expect(create(payload(positionId, itId), headToken), 201));
        Map<String, Object> before = row(draft);
        Map<Integer, String> outOfRange = new LinkedHashMap<>();
        outOfRange.put(0, "Số lượng cần tuyển phải lớn hơn 0.");
        outOfRange.put(-1, "Số lượng cần tuyển phải lớn hơn 0.");
        outOfRange.put(1_000, HEADCOUNT_MAX_MESSAGE);
        outOfRange.put(Integer.MAX_VALUE, HEADCOUNT_MAX_MESSAGE);
        for (var headcount : outOfRange.entrySet()) {
            Map<String, Object> body = payload(positionId, itId);
            body.put("headcount", headcount.getKey());
            assertThat(fieldErrors(create(body, headToken), "headcount").path("headcount").asText())
                    .as("POST %d", headcount.getKey()).isEqualTo(headcount.getValue());
            assertThat(fieldErrors(put(draft, body, headToken), "headcount").path("headcount").asText())
                    .as("PUT %d", headcount.getKey()).isEqualTo(headcount.getValue());
        }
        assertThat(count()).isEqualTo(1);
        assertThat(row(draft)).isEqualTo(before);

        // Both ends of the range are accepted and stored exactly.
        Map<String, Object> largest = payload(positionId, itId);
        largest.put("headcount", 999);
        UUID large = id(expect(create(largest, headToken), 201));
        assertThat(row(large).get("headcount")).isEqualTo(999);
        Map<String, Object> smallest = payload(positionId, itId);
        smallest.put("headcount", 1);
        assertThat(expect(put(large, smallest, headToken), 200).path("headcount").asInt()).isEqualTo(1);
        assertThat(row(large).get("headcount")).isEqualTo(1);
    }

    @Test
    void textSectionsKeepTheirSizeLimitsOnUpdate() throws Exception {
        UUID draft = id(expect(create(payload(positionId, itId), headToken), 201));
        Map<String, Object> before = row(draft);
        Map<String, Integer> limits = Map.of("salaryJustification", 2_000, "jobDescription", 10_000,
                "candidateRequirements", 10_000);
        for (var limit : limits.entrySet()) {
            Map<String, Object> tooLong = payload(positionId, itId);
            tooLong.put(limit.getKey(), "x".repeat(limit.getValue() + 1));
            fieldErrors(put(draft, tooLong, headToken), limit.getKey());
        }
        assertThat(row(draft)).isEqualTo(before);

        Map<String, Object> atTheLimit = payload(positionId, itId);
        limits.forEach((field, limit) -> atTheLimit.put(field, "y".repeat(limit)));
        expect(put(draft, atTheLimit, headToken), 200);
        assertThat(jdbc.queryForObject("""
                SELECT length(salary_justification) = 2000 AND length(job_description) = 10000
                   AND length(candidate_requirements) = 10000
                FROM recruitment_requisitions WHERE id = ?
                """, Boolean.class, draft)).isTrue();
    }

    @Test
    void aNewDraftNeedsAnActivePositionAndAnActiveDepartment() throws Exception {
        jdbc.update("UPDATE positions SET active = FALSE WHERE id = ?", otherPositionId);
        UUID closedId = department("CLOSED", null, headId, false);

        var inactivePosition = create(payload(otherPositionId, itId), headToken);
        noStore(inactivePosition);
        businessError(inactivePosition, "REQUISITION_POSITION_INACTIVE", "positionId", POSITION_INACTIVE_MESSAGE);
        businessError(create(payload(positionId, closedId), headToken),
                "REQUISITION_DEPARTMENT_INACTIVE", "departmentId", DEPARTMENT_INACTIVE_MESSAGE);
        // One error at a time, position first: both inactive reports the position.
        businessError(create(payload(otherPositionId, closedId), headToken),
                "REQUISITION_POSITION_INACTIVE", "positionId", POSITION_INACTIVE_MESSAGE);
        // An unknown id is still "does not exist", not "inactive".
        businessError(create(payload(UUID.randomUUID(), closedId), headToken),
                "INVALID_REQUISITION_POSITION", "positionId", "Chức danh không tồn tại.");
        businessError(create(payload(positionId, UUID.randomUUID()), headToken),
                "INVALID_REQUISITION_DEPARTMENT", "departmentId", "Phòng ban không tồn tại.");
        assertThat(count()).isZero();

        // Only the chosen department's own flag counts: an active team below a closed parent still hires.
        UUID openChild = department("CLOSED_TEAM", closedId, headId, true);
        JsonNode saved = expect(create(payload(positionId, openChild), headToken), 201);
        assertThat(saved.path("departmentId").asText()).isEqualTo(openChild.toString());
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void aDraftIsSavedAgainOnlyWithAnActivePositionAndDepartmentEvenIfItAlreadyUsesThem() throws Exception {
        UUID draft = id(expect(create(payload(positionId, itId), headToken), 201));
        Map<String, Object> before = row(draft);

        // HR stops using the position after the draft was written. Reading the draft still works.
        jdbc.update("UPDATE positions SET active = FALSE WHERE id = ?", positionId);
        assertThat(expect(get(draft, headToken), 200).path("positionId").asText()).isEqualTo(positionId.toString());
        var samePosition = put(draft, changed(positionId, itId), headToken);
        noStore(samePosition);
        businessError(samePosition, "REQUISITION_POSITION_INACTIVE", "positionId", POSITION_INACTIVE_MESSAGE);
        assertThat(row(draft)).isEqualTo(before);
        // Choosing another, active position saves the draft.
        assertThat(expect(put(draft, changed(otherPositionId, itId), headToken), 200).path("positionId").asText())
                .isEqualTo(otherPositionId.toString());

        // Same for the department: the head still sees the draft of a closed department but cannot save it there,
        // and cannot move a draft into a closed department either.
        jdbc.update("UPDATE departments SET active = FALSE WHERE id = ?", itId);
        Map<String, Object> saved = row(draft);
        assertThat(expect(get(draft, headToken), 200).path("departmentId").asText()).isEqualTo(itId.toString());
        businessError(put(draft, changed(otherPositionId, itId), headToken),
                "REQUISITION_DEPARTMENT_INACTIVE", "departmentId", DEPARTMENT_INACTIVE_MESSAGE);
        UUID closedTeam = department("IT_CLOSED_TEAM", itId, headId, false);
        businessError(put(draft, changed(otherPositionId, closedTeam), headToken),
                "REQUISITION_DEPARTMENT_INACTIVE", "departmentId", DEPARTMENT_INACTIVE_MESSAGE);
        assertThat(row(draft)).isEqualTo(saved);
        // Once HR turns the department back on, the same body is saved.
        jdbc.update("UPDATE departments SET active = TRUE WHERE id = ?", itId);
        expect(put(draft, changed(otherPositionId, itId), headToken), 200);
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void callersWithoutAccessAreForbiddenBeforeThePositionAndDepartmentAreChecked() throws Exception {
        UUID draft = id(expect(create(payload(positionId, itId), headToken), 201));
        Map<String, Object> before = row(draft);
        jdbc.update("UPDATE positions SET active = FALSE WHERE id = ?", otherPositionId);

        // INTERVIEWER has no requisition permission: the URL rule refuses before the body is read.
        account("interviewer@example.test", Set.of(Role.INTERVIEWER));
        String interviewerToken = token("interviewer@example.test");
        Map<String, Object> broken = payload(otherPositionId, itId);
        broken.put("reason", "PROMOTION");
        broken.put("headcount", 1_000);
        forbidden(create(broken, interviewerToken));
        forbidden(put(draft, broken, interviewerToken));
        // The SALES head may write requisitions but not this IT draft: the scope check (after locking the draft)
        // comes before the position and department checks, so the answer is 403 although the position is closed.
        String salesToken = token("sales@example.test");
        forbidden(put(draft, changed(otherPositionId, itId), salesToken));
        assertThat(row(draft)).isEqualTo(before);
        assertThat(count()).isEqualTo(1);
        // Field checks that need no stored data (@Valid) still come first, as in the documented order of checks:
        // a wrong reason code gets the same 400 for every caller who passes the URL rule.
        Map<String, Object> wrongReason = changed(positionId, itId);
        wrongReason.put("reason", "PROMOTION");
        assertThat(reasonError(put(draft, wrongReason, salesToken))).isEqualTo(REASON_MESSAGE);
        assertThat(row(draft)).isEqualTo(before);
    }

    // Without FOR SHARE, a plain read would not wait and would still see the old "active" value, so the draft would
    // be saved on a position or department HR had just closed. With it, the request waits for HR's transaction
    // and then follows its outcome.
    @ParameterizedTest
    @CsvSource({"positions, true", "positions, false", "departments, true", "departments, false"})
    void createWaitsForAnUncommittedDeactivationAndFollowsItsOutcome(String table, boolean commit) throws Exception {
        UUID target = table.equals("positions") ? positionId : itId;
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blockerPid = backendPid(connection);
            assertThat(update(connection, "UPDATE " + table + " SET active = FALSE WHERE id = ?", target)).isEqualTo(1);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(() -> create(payload(positionId, itId), headToken));
                try {
                    awaitWaiters(blockerPid, 1);
                    if (commit) { connection.commit(); } else { connection.rollback(); }
                    var result = response.get(10, TimeUnit.SECONDS);
                    if (!commit) {
                        expect(result, 201);
                    } else if (table.equals("positions")) {
                        businessError(result, "REQUISITION_POSITION_INACTIVE", "positionId", POSITION_INACTIVE_MESSAGE);
                    } else {
                        businessError(result, "REQUISITION_DEPARTMENT_INACTIVE", "departmentId",
                                DEPARTMENT_INACTIVE_MESSAGE);
                    }
                } finally {
                    connection.rollback();
                }
            }
        }
        assertThat(count()).isEqualTo(commit ? 0 : 1);
    }

    // The other direction: while a requisition transaction holds the checked rows, HR's deactivation must wait, so
    // it cannot slip in between the check and the commit. lock_timeout turns that wait into a quick error.
    @Test
    void theActiveChecksMakeADeactivationWaitUntilTheTransactionEnds() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(positionRepository.findActiveForShare(positionId)).contains(true);
            assertThat(departmentRepository.findActiveForShare(itId)).contains(true);
            assertThat(positionRepository.findActiveForShare(UUID.randomUUID())).isEmpty();
            assertThat(departmentRepository.findActiveForShare(UUID.randomUUID())).isEmpty();
            for (var locked : Map.of("positions", positionId, "departments", itId).entrySet()) {
                try (var connection = dataSource.getConnection()) {
                    connection.setAutoCommit(false);
                    update(connection, "SET LOCAL lock_timeout = '300ms'");
                    assertThatThrownBy(() -> update(connection,
                            "UPDATE " + locked.getKey() + " SET active = FALSE WHERE id = ?", locked.getValue()))
                            .as(locked.getKey()).isInstanceOfSatisfying(SQLException.class,
                                    exception -> assertThat(exception.getSQLState()).isEqualTo("55P03"));
                    connection.rollback();
                    // Another shared reader (for example a second requisition being saved) is not blocked.
                    update(connection, "SET LOCAL lock_timeout = '300ms'");
                    try (var statement = connection.prepareStatement(
                            "SELECT active FROM " + locked.getKey() + " WHERE id = ? FOR SHARE")) {
                        statement.setObject(1, locked.getValue());
                        try (var row = statement.executeQuery()) {
                            assertThat(row.next()).isTrue();
                            assertThat(row.getBoolean(1)).isTrue();
                        }
                    }
                    connection.rollback();
                } catch (SQLException exception) {
                    throw new IllegalStateException(exception);
                }
            }
        });
        // After the transaction the rows are free again.
        assertThat(jdbc.update("UPDATE positions SET active = FALSE WHERE id = ?", positionId)).isEqualTo(1);
        assertThat(jdbc.update("UPDATE departments SET active = FALSE WHERE id = ?", itId)).isEqualTo(1);
    }

    private Map<String, Object> payload(UUID position, UUID department) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("positionId", position);
        result.put("departmentId", department);
        result.put("headcount", 2);
        result.put("reason", "NEW_HEADCOUNT");
        return result;
    }

    // A valid update body that differs from payload(), so a successful PUT is visible in the row.
    private Map<String, Object> changed(UUID position, UUID department) {
        Map<String, Object> result = payload(position, department);
        result.put("headcount", 5);
        result.put("reason", "REPLACEMENT");
        return result;
    }

    // A valid body as JSON text with one field written raw, so the test controls the exact JSON value.
    private String withRawField(String field, String rawJson) throws Exception {
        Map<String, Object> body = payload(positionId, itId);
        body.remove(field);
        String base = json.writeValueAsString(body);
        return base.substring(0, base.length() - 1) + ",\"" + field + "\":" + rawJson + "}";
    }

    private UUID account(String email, Set<Role> roles) {
        return accounts.saveAndFlush(new Account(email, "Requisition test", fixturePasswordHash, roles, START)).getId();
    }

    private UUID department(String code, UUID parent, UUID manager, boolean active) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO departments (id, code, name, parent_id, manager_user_id, active, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, code, "Phòng " + code, parent, manager, active, Timestamp.from(START));
        return id;
    }

    private UUID position(String code) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO positions (id, code, name, level, salary_min, salary_max, active, created_at, updated_at)
                VALUES (?, ?, 'Lập trình viên', 'Junior', 15000000, 25000000, TRUE, ?, ?)
                """, id, code, Timestamp.from(START), Timestamp.from(START));
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
                json.writeValueAsString(Map.of("email", email, "password", PASSWORD)), null), 200);
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

    private JsonNode expect(HttpResponse<String> response, int expected) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expected);
        return json.readTree(response.body());
    }

    private void error(HttpResponse<String> response, int status, String code) {
        assertThat(expect(response, status).path("code").asText()).isEqualTo(code);
    }

    private void forbidden(HttpResponse<String> response) {
        JsonNode body = expect(response, 403);
        assertThat(body.path("code").asText()).isEqualTo("FORBIDDEN");
        assertThat(body.path("message").asText()).isEqualTo("Bạn không có quyền thực hiện thao tác này.");
        noStore(response);
    }

    // A 400 VALIDATION_ERROR whose fieldErrors names exactly these request fields.
    private JsonNode fieldErrors(HttpResponse<String> response, String... fields) {
        JsonNode body = expect(response, 400);
        assertThat(body.path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.path("message").asText()).isEqualTo(FORM_MESSAGE);
        assertThat(body.path("fieldErrors").size()).as(body.toString()).isEqualTo(fields.length);
        for (String field : fields) {
            assertThat(body.path("fieldErrors").path(field).asText()).as(field).isNotBlank();
        }
        return body.path("fieldErrors");
    }

    // The message of a VALIDATION_ERROR that is only about the reason field.
    private String reasonError(HttpResponse<String> response) {
        return fieldErrors(response, "reason").path("reason").asText();
    }

    // A 400 business error about one field: the same text is the message and the field's form error.
    private void businessError(HttpResponse<String> response, String code, String field, String message) {
        JsonNode body = expect(response, 400);
        assertThat(body.path("code").asText()).isEqualTo(code);
        assertThat(body.path("message").asText()).isEqualTo(message);
        assertThat(body.path("fieldErrors").size()).as(body.toString()).isEqualTo(1);
        assertThat(body.path("fieldErrors").path(field).asText()).isEqualTo(message);
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
