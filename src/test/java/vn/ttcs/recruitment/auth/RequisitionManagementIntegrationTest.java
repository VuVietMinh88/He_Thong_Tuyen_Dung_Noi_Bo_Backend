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
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// Task 244: POST /api/v1/requisitions saves a draft owned by the caller.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RequisitionManagementIntegrationTest {
    private static final String BASE = "/api/v1/requisitions";
    private static final String PASSWORD = "TestingOnly123!";
    private static final Instant START = Instant.parse("2026-10-07T00:00:00Z");
    // Larger than Integer.MAX_VALUE, so proposed salaries must stay whole-dong long values end to end.
    private static final long THREE_BILLION_VND = 3_000_000_000L;
    // The largest salary the API accepts: 1.000 tỷ đồng, the same ceiling as positions.
    private static final long SALARY_CEILING = 1_000_000_000_000L;
    // Leading spaces, blank lines and the final line break must all be stored exactly as typed.
    private static final String JOB_DESCRIPTION = "  Phát triển API tuyển dụng.\n\n- Spring Boot\n- PostgreSQL\n";
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
    private UUID adminId;
    private String fixturePasswordHash;
    // The department head (HIRING_MANAGER) who manages the fixture department and writes the drafts.
    private UUID headId;
    private String headToken;
    private UUID departmentId;
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
        adminId = UUID.fromString(login("admin@example.test").path("user").path("id").asText());
        fixturePasswordHash = accounts.findById(adminId).orElseThrow().getPasswordHash();
        headId = account("head@example.test", Set.of(Role.HIRING_MANAGER));
        headToken = login("head@example.test").path("accessToken").asText();
        departmentId = department("IT", headId);
        positionId = position("DEV_JUNIOR");
    }

    @Test
    void createsCompleteDraftOwnedByTheCallerKeepingTextsAndWholeVndSalariesExactly() throws Exception {
        // Nanoseconds in the clock: the response must show the microseconds PostgreSQL keeps.
        clock.set(START.plusNanos(123_456_789));
        Instant expectedTime = START.plusNanos(123_456_000);
        Map<String, Object> body = payload(positionId, departmentId);
        body.put("proposedSalaryMin", 2_000_000_000L);
        body.put("proposedSalaryMax", THREE_BILLION_VND);
        body.put("salaryJustification", "Cần người có kinh nghiệm quản lý dự án lớn.");
        body.put("neededBy", "2026-12-31");
        body.put("jobDescription", JOB_DESCRIPTION);
        body.put("candidateRequirements", "Tối thiểu 2 năm kinh nghiệm Java.");

        var response = create(body, headToken);
        JsonNode result = expect(response, 201);
        noStore(response);
        // Exactly these fields: no standard salary band of the position, nothing else from the server.
        assertThat(result.size()).isEqualTo(RESPONSE_FIELDS.size());
        RESPONSE_FIELDS.forEach(field -> assertThat(result.has(field)).as(field).isTrue());
        UUID id = UUID.fromString(result.path("id").asText());
        assertThat(result.path("positionId").asText()).isEqualTo(positionId.toString());
        assertThat(result.path("departmentId").asText()).isEqualTo(departmentId.toString());
        assertThat(result.path("headcount").asInt()).isEqualTo(2);
        assertThat(result.path("reason").asText()).isEqualTo("NEW_HEADCOUNT");
        assertThat(result.path("proposedSalaryMin").asLong()).isEqualTo(2_000_000_000L);
        assertThat(result.path("proposedSalaryMax").asLong()).isEqualTo(THREE_BILLION_VND);
        assertThat(result.path("salaryJustification").asText()).isEqualTo("Cần người có kinh nghiệm quản lý dự án lớn.");
        assertThat(result.path("neededBy").asText()).isEqualTo("2026-12-31");
        assertThat(result.path("jobDescription").asText()).isEqualTo(JOB_DESCRIPTION);
        assertThat(result.path("candidateRequirements").asText()).isEqualTo("Tối thiểu 2 năm kinh nghiệm Java.");
        assertThat(result.path("status").asText()).isEqualTo("DRAFT");
        assertThat(result.path("createdBy").asText()).isEqualTo(headId.toString());
        assertThat(Instant.parse(result.path("createdAt").asText())).isEqualTo(expectedTime);
        assertThat(Instant.parse(result.path("updatedAt").asText())).isEqualTo(expectedTime);

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM recruitment_requisitions WHERE id = ?", id);
        assertThat(row.get("position_id")).isEqualTo(positionId);
        assertThat(row.get("department_id")).isEqualTo(departmentId);
        assertThat(row.get("headcount")).isEqualTo(2);
        assertThat(row.get("reason")).isEqualTo("NEW_HEADCOUNT");
        assertThat(row.get("proposed_salary_min")).isEqualTo(2_000_000_000L);
        assertThat(row.get("proposed_salary_max")).isEqualTo(THREE_BILLION_VND);
        assertThat(row.get("job_description")).isEqualTo(JOB_DESCRIPTION);
        assertThat(row.get("status")).isEqualTo("DRAFT");
        assertThat(row.get("created_by")).isEqualTo(headId);
        assertThat(row.get("created_at")).isEqualTo(Timestamp.from(expectedTime));
        assertThat(row.get("updated_at")).isEqualTo(Timestamp.from(expectedTime));
        assertThat(jdbc.queryForObject("SELECT needed_by FROM recruitment_requisitions WHERE id = ?", LocalDate.class, id))
                .isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void savesDraftWithOnlyTheRequiredFieldsAndStoresBlankSectionsAsNull() throws Exception {
        Map<String, Object> body = payload(positionId, departmentId);
        body.put("headcount", 1);
        body.put("reason", "REPLACEMENT");
        body.put("salaryJustification", "   ");
        body.put("neededBy", null);
        body.put("jobDescription", " \n\t ");
        body.put("candidateRequirements", "");

        JsonNode result = expect(create(body, headToken), 201);
        assertThat(result.path("headcount").asInt()).isEqualTo(1);
        assertThat(result.path("reason").asText()).isEqualTo("REPLACEMENT");
        for (String field : List.of("proposedSalaryMin", "proposedSalaryMax", "salaryJustification", "neededBy",
                "jobDescription", "candidateRequirements")) {
            assertThat(result.has(field)).as(field).isTrue();
            assertThat(result.path(field).isNull()).as(field).isTrue();
        }
        UUID first = UUID.fromString(result.path("id").asText());
        assertThat(jdbc.queryForObject("""
                SELECT proposed_salary_min IS NULL AND proposed_salary_max IS NULL AND salary_justification IS NULL
                   AND needed_by IS NULL AND job_description IS NULL AND candidate_requirements IS NULL
                FROM recruitment_requisitions WHERE id = ?
                """, Boolean.class, first)).isTrue();

        // Sending the same content again saves a second draft; it never overwrites the first one.
        UUID second = UUID.fromString(expect(create(body, headToken), 201).path("id").asText());
        assertThat(second).isNotEqualTo(first);
        assertThat(count()).isEqualTo(2);
    }

    @Test
    void rejectsMissingRequiredFieldsAndOutOfRangeValuesButAcceptsTheLimits() throws Exception {
        Map<String, Object> valid = payload(positionId, departmentId);
        for (String field : List.of("positionId", "departmentId", "headcount", "reason")) {
            Map<String, Object> invalid = new LinkedHashMap<>(valid);
            invalid.remove(field);
            fieldErrors(create(invalid, headToken), field);
            invalid.put(field, null);
            fieldErrors(create(invalid, headToken), field);
        }
        for (int headcount : List.of(0, -3)) {
            Map<String, Object> invalid = new LinkedHashMap<>(valid);
            invalid.put("headcount", headcount);
            assertThat(fieldErrors(create(invalid, headToken), "headcount").path("headcount").asText())
                    .isEqualTo("Số lượng cần tuyển phải lớn hơn 0.");
        }
        Map<String, Object> negative = new LinkedHashMap<>(valid);
        negative.put("proposedSalaryMin", -1L);
        assertThat(fieldErrors(create(negative, headToken), "proposedSalaryMin").path("proposedSalaryMin").asText())
                .isEqualTo("Lương đề xuất tối thiểu không được âm.");
        Map<String, Object> tooLarge = new LinkedHashMap<>(valid);
        tooLarge.put("proposedSalaryMax", SALARY_CEILING + 1);
        assertThat(fieldErrors(create(tooLarge, headToken), "proposedSalaryMax").path("proposedSalaryMax").asText())
                .isEqualTo("Lương đề xuất tối đa không được vượt quá 1.000.000.000.000 đồng.");
        Map<String, Integer> textLimits = Map.of("salaryJustification", 2_000, "jobDescription", 10_000,
                "candidateRequirements", 10_000);
        for (var limit : textLimits.entrySet()) {
            Map<String, Object> tooLong = new LinkedHashMap<>(valid);
            tooLong.put(limit.getKey(), "x".repeat(limit.getValue() + 1));
            fieldErrors(create(tooLong, headToken), limit.getKey());
        }
        // Several broken fields are reported together, so a form can mark all of them in one round trip.
        Map<String, Object> allWrong = new LinkedHashMap<>(valid);
        allWrong.remove("positionId");
        allWrong.put("headcount", 0);
        allWrong.put("jobDescription", "x".repeat(10_001));
        fieldErrors(create(allWrong, headToken), "positionId", "headcount", "jobDescription");
        assertThat(count()).isZero();

        // The limits themselves are accepted and stored exactly.
        Map<String, Object> limits = new LinkedHashMap<>(valid);
        limits.put("headcount", 1);
        limits.put("proposedSalaryMin", 0L);
        limits.put("proposedSalaryMax", SALARY_CEILING);
        textLimits.forEach((field, limit) -> limits.put(field, "y".repeat(limit)));
        UUID saved = UUID.fromString(expect(create(limits, headToken), 201).path("id").asText());
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT proposed_salary_min, proposed_salary_max, length(salary_justification) AS justification,
                       length(job_description) AS description, length(candidate_requirements) AS requirements
                FROM recruitment_requisitions WHERE id = ?
                """, saved);
        assertThat(row.get("proposed_salary_min")).isEqualTo(0L);
        assertThat(row.get("proposed_salary_max")).isEqualTo(SALARY_CEILING);
        assertThat(row.get("justification")).isEqualTo(2_000);
        assertThat(row.get("description")).isEqualTo(10_000);
        assertThat(row.get("requirements")).isEqualTo(10_000);
    }

    @Test
    void rejectsValuesOfTheWrongJsonTypeAndFieldsTheServerDecides() throws Exception {
        // A wrong reason code is a VALIDATION_ERROR on "reason" since task 246 (RequisitionValidationIntegrationTest).
        Map<String, String> invalidJson = new LinkedHashMap<>();
        invalidJson.put("positionId", "\"not-a-uuid\"");
        invalidJson.put("neededBy", "\"31/12/2026\"");
        // Jackson would otherwise store 1.5 as 1 or convert the text; a salary must be a JSON whole number.
        invalidJson.put("proposedSalaryMin", "1.5");
        invalidJson.put("proposedSalaryMax", "\"15000000\"");
        // Status, owner and id are set by the server, so a client cannot send them.
        invalidJson.put("status", "\"APPROVED\"");
        invalidJson.put("createdBy", "\"" + adminId + "\"");
        invalidJson.put("id", "\"" + UUID.randomUUID() + "\"");
        for (var entry : invalidJson.entrySet()) {
            error(request("POST", BASE, withRawField(entry.getKey(), entry.getValue()), headToken), 400, "INVALID_JSON");
        }
        // An impossible calendar date is not silently moved.
        error(request("POST", BASE, withRawField("neededBy", "\"2026-02-30\""), headToken), 400, "INVALID_JSON");
        // A headcount must be a JSON whole number as well: 1.5 is not cut to 1, 0.9 is not cut to 0 (which would
        // give the misleading "must be greater than 0" message) and "2" is not converted. 3000000000 is outside int.
        for (String headcount : List.of("1.5", "0.9", "2.0", "1e1", "\"2\"", "true", "3000000000")) {
            error(request("POST", BASE, withRawField("headcount", headcount), headToken), 400, "INVALID_JSON");
        }
        assertThat(count()).isZero();
    }

    @Test
    void rejectsTextSectionsContainingTheNulCharacterThatPostgreSqlCannotStore() throws Exception {
        Map<String, String> messages = Map.of(
                "salaryJustification", "Giải trình lương chứa ký tự không hợp lệ.",
                "jobDescription", "Mô tả công việc chứa ký tự không hợp lệ.",
                "candidateRequirements", "Yêu cầu ứng viên chứa ký tự không hợp lệ.");
        for (var field : messages.entrySet()) {
            Map<String, Object> body = payload(positionId, departmentId);
            // The NUL character (code 0): a TEXT column cannot hold it, so without this check the insert fails (500).
            body.put(field.getKey(), "Nội dung" + (char) 0 + " dán từ tệp khác");
            assertThat(fieldErrors(create(body, headToken), field.getKey()).path(field.getKey()).asText())
                    .isEqualTo(field.getValue());
        }
        assertThat(count()).isZero();

        // Only NUL is refused: tabs and Windows line breaks are formatting and are stored exactly.
        String formatted = "Cột 1\tCột 2\r\nDòng 2";
        Map<String, Object> body = payload(positionId, departmentId);
        messages.keySet().forEach(field -> body.put(field, formatted));
        JsonNode saved = expect(create(body, headToken), 201);
        UUID id = UUID.fromString(saved.path("id").asText());
        messages.keySet().forEach(field -> assertThat(saved.path(field).asText()).as(field).isEqualTo(formatted));
        assertThat(jdbc.queryForObject("""
                SELECT salary_justification = ? AND job_description = ? AND candidate_requirements = ?
                FROM recruitment_requisitions WHERE id = ?
                """, Boolean.class, formatted, formatted, formatted, id)).isTrue();
    }

    @Test
    void rejectsAnInvertedSalaryProposalButAcceptsEqualOrOneSidedProposals() throws Exception {
        Map<String, Object> inverted = payload(positionId, departmentId);
        inverted.put("proposedSalaryMin", 20_000_001L);
        inverted.put("proposedSalaryMax", 20_000_000L);
        var response = create(inverted, headToken);
        JsonNode body = expect(response, 400);
        noStore(response);
        assertThat(body.path("code").asText()).isEqualTo("REQUISITION_SALARY_RANGE_INVALID");
        assertThat(body.path("message").asText()).isEqualTo("Lương đề xuất tối thiểu không được lớn hơn lương đề xuất tối đa.");
        assertThat(body.path("fieldErrors").size()).isEqualTo(1);
        assertThat(body.path("fieldErrors").path("proposedSalaryMax").asText())
                .isEqualTo("Lương đề xuất tối đa phải lớn hơn hoặc bằng lương đề xuất tối thiểu.");
        assertThat(count()).isZero();

        // A fixed salary (min = max) and a proposal with only one end are valid while the draft is being written.
        List<Long[]> proposals = List.of(new Long[] {20_000_000L, 20_000_000L}, new Long[] {20_000_000L, null},
                new Long[] {null, 20_000_000L});
        for (Long[] proposal : proposals) {
            Map<String, Object> draft = payload(positionId, departmentId);
            draft.put("proposedSalaryMin", proposal[0]);
            draft.put("proposedSalaryMax", proposal[1]);
            JsonNode saved = expect(create(draft, headToken), 201);
            assertThat(saved.path("proposedSalaryMin").isNull()).isEqualTo(proposal[0] == null);
            assertThat(saved.path("proposedSalaryMax").isNull()).isEqualTo(proposal[1] == null);
            if (proposal[0] != null) { assertThat(saved.path("proposedSalaryMin").asLong()).isEqualTo(proposal[0]); }
            if (proposal[1] != null) { assertThat(saved.path("proposedSalaryMax").asLong()).isEqualTo(proposal[1]); }
        }
        assertThat(count()).isEqualTo(proposals.size());
    }

    @Test
    void rejectsUnknownPositionOrDepartmentAsFormErrorsWithoutSavingAnything() throws Exception {
        var unknownPosition = create(payload(UUID.randomUUID(), departmentId), headToken);
        JsonNode positionError = expect(unknownPosition, 400);
        noStore(unknownPosition);
        assertThat(positionError.path("code").asText()).isEqualTo("INVALID_REQUISITION_POSITION");
        assertThat(positionError.path("fieldErrors").path("positionId").asText()).isEqualTo("Chức danh không tồn tại.");

        JsonNode departmentError = expect(create(payload(positionId, UUID.randomUUID()), headToken), 400);
        assertThat(departmentError.path("code").asText()).isEqualTo("INVALID_REQUISITION_DEPARTMENT");
        assertThat(departmentError.path("fieldErrors").path("departmentId").asText()).isEqualTo("Phòng ban không tồn tại.");
        assertThat(count()).isZero();
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void everyRoleWithARequisitionWritePermissionCanSaveADraftAndOthersAreForbidden(Role role) throws Exception {
        UUID caller = account("role@example.test", Set.of(role));
        String token = login("role@example.test").path("accessToken").asText();
        // The caller manages this department, so the department-scope rule of task 249 keeps this result valid.
        UUID managed = department("OWN", caller);
        Set<String> grants = RolePermissionSeedMigrationTest.EXPECTED_GRANTS.get(role.name());
        boolean writer = grants.contains("REQUISITIONS_WRITE_ALL") || grants.contains("REQUISITIONS_WRITE_SCOPED");
        // Written out as well, so this test does not only repeat the seed: only INTERVIEWER has no requisition rights.
        assertThat(writer).isEqualTo(role != Role.INTERVIEWER);

        var response = create(payload(positionId, managed), token);
        if (writer) {
            assertThat(expect(response, 201).path("createdBy").asText()).isEqualTo(caller.toString());
            assertThat(count()).isEqualTo(1);
        } else {
            forbidden(response);
            assertThat(count()).isZero();
        }
    }

    @Test
    void anonymousCallersAndAccountsWithoutRolesCannotCreateDrafts() throws Exception {
        var anonymous = create(payload(positionId, departmentId), null);
        assertThat(expect(anonymous, 401).path("code").asText()).isEqualTo("UNAUTHORIZED");
        assertThat(anonymous.headers().firstValue("WWW-Authenticate").orElseThrow()).startsWith("Bearer");
        assertThat(create(payload(positionId, departmentId), "not-a-jwt").statusCode()).isEqualTo(401);

        account("norole@example.test", Set.of());
        forbidden(create(payload(positionId, departmentId), login("norole@example.test").path("accessToken").asText()));
        assertThat(count()).isZero();
    }

    @Test
    void removedWritePermissionIsEnforcedOnTheNextRequestWithTheSameAccessToken() throws Exception {
        jdbc.update("DELETE FROM role_permissions WHERE role_code = 'HIRING_MANAGER' AND permission_code = 'REQUISITIONS_WRITE_SCOPED'");
        try {
            // REQUISITIONS_READ_SCOPED is still granted, but reading never allows writing.
            forbidden(create(payload(positionId, departmentId), headToken));
            assertThat(count()).isZero();
        } finally {
            grant("HIRING_MANAGER", "REQUISITIONS_WRITE_SCOPED");
        }
        expect(create(payload(positionId, departmentId), headToken), 201);
    }

    @ParameterizedTest
    @ValueSource(strings = {"lost-permission", "expired-jwt", "locked-actor", "revoked-session"})
    void rechecksAccessAfterWaitingForTheActorAccountLock(String change) throws Exception {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blockerPid = lockAccount(connection, headId);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(() -> create(payload(positionId, departmentId), headToken));
                try {
                    awaitWaiters(blockerPid, 1);
                    switch (change) {
                        case "lost-permission" -> jdbc.update("DELETE FROM role_permissions "
                                + "WHERE role_code = 'HIRING_MANAGER' AND permission_code = 'REQUISITIONS_WRITE_SCOPED'");
                        case "expired-jwt" -> clock.set(START.plus(Duration.ofMinutes(15)));
                        // The bootstrap admin is outside the request, so referencing it causes no lock interference.
                        case "locked-actor" -> execute(connection, "UPDATE user_accounts SET admin_locked_at = ?, "
                                + "admin_lock_reason = 'Review', admin_locked_by = ? WHERE id = ?",
                                Timestamp.from(START), adminId, headId);
                        default -> execute(connection, "UPDATE auth_sessions SET revoked_at = ? WHERE user_id = ?",
                                Timestamp.from(START), headId);
                    }
                    connection.commit();
                    var result = response.get(10, TimeUnit.SECONDS);
                    if (change.equals("lost-permission")) {
                        forbidden(result);
                    } else {
                        error(result, 401, "SESSION_INVALID");
                    }
                } finally {
                    connection.rollback();
                }
            }
        } finally {
            grant("HIRING_MANAGER", "REQUISITIONS_WRITE_SCOPED");
        }
        assertThat(count()).isZero();
    }

    private Map<String, Object> payload(UUID position, UUID department) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("positionId", position);
        result.put("departmentId", department);
        result.put("headcount", 2);
        result.put("reason", "NEW_HEADCOUNT");
        return result;
    }

    // A valid draft as JSON text with one field written raw, so the test controls the exact JSON value.
    private String withRawField(String field, String rawJson) throws Exception {
        Map<String, Object> body = payload(positionId, departmentId);
        body.remove(field);
        String base = json.writeValueAsString(body);
        return base.substring(0, base.length() - 1) + ",\"" + field + "\":" + rawJson + "}";
    }

    private UUID account(String email, Set<Role> roles) {
        return accounts.saveAndFlush(new Account(email, "Requisition test", fixturePasswordHash, roles, START)).getId();
    }

    private UUID department(String code, UUID manager) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO departments (id, code, name, manager_user_id, active, created_at) VALUES (?, ?, ?, ?, TRUE, ?)",
                id, code, "Phòng " + code, manager, Timestamp.from(START));
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

    private void grant(String role, String permission) {
        jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES (?, ?) ON CONFLICT DO NOTHING",
                role, permission);
    }

    private int count() {
        return jdbc.queryForObject("SELECT count(*) FROM recruitment_requisitions", Integer.class);
    }

    private JsonNode login(String email) throws Exception {
        return expect(request("POST", "/api/v1/auth/login",
                json.writeValueAsString(Map.of("email", email, "password", PASSWORD)), null), 200);
    }

    private HttpResponse<String> create(Map<String, Object> payload, String token) throws Exception {
        return request("POST", BASE, json.writeValueAsString(payload), token);
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
        assertThat(body.path("fieldErrors").size()).as(body.toString()).isEqualTo(fields.length);
        for (String field : fields) {
            assertThat(body.path("fieldErrors").path(field).asText()).as(field).isNotBlank();
        }
        return body.path("fieldErrors");
    }

    private void noStore(HttpResponse<String> response) {
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
    }

    private int lockAccount(Connection connection, UUID id) throws Exception {
        try (var statement = connection.prepareStatement("SELECT pg_backend_pid() FROM user_accounts WHERE id = ? FOR UPDATE")) {
            statement.setObject(1, id);
            try (var row = statement.executeQuery()) { assertThat(row.next()).isTrue(); return row.getInt(1); }
        }
    }

    private void execute(Connection connection, String sql, Object... values) throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) { statement.setObject(index + 1, values[index]); }
            assertThat(statement.executeUpdate()).isEqualTo(1);
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
