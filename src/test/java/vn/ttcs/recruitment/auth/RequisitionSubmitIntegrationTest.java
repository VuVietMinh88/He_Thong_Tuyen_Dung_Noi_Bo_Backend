package vn.ttcs.recruitment.auth;

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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.account.BootstrapAdmin;
import vn.ttcs.recruitment.account.Role;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Task 257: sending a requisition for approval takes a snapshot of its approval chain (story S3-01, S3-02).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RequisitionSubmitIntegrationTest {
    private static final String BASE = "/api/v1/requisitions/";
    private static final String PASSWORD = "TestingOnly123!";
    // 10:00 on 11 Oct 2026 in Vietnam.
    private static final Instant START = Instant.parse("2026-10-11T03:00:00Z");

    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccountRepository accounts;
    @Autowired private BootstrapAdmin bootstrap;
    @Autowired private AuthIntegrationTest.MutableClock clock;
    @Autowired private PlatformTransactionManager transactions;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private String fixturePasswordHash;
    private UUID hrId;
    private String hrToken;
    private UUID headId;
    private String headToken;
    private String salesToken;
    private UUID approverA;
    private UUID itId;
    private UUID salesId;
    private UUID positionId;
    private UUID flowId;

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
        jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('HIRING_MANAGER', 'REQUISITIONS_READ_SCOPED') ON CONFLICT DO NOTHING");
        // Approval-flow versions and the approval history are never deleted (triggers), but TRUNCATE does not fire them.
        jdbc.execute("TRUNCATE requisition_workflow_events, approval_flow_steps, approval_flow_versions, approval_flows");
        jdbc.update("DELETE FROM requisition_recruiter_changes");
        jdbc.update("DELETE FROM requisition_recruiters");
        jdbc.update("DELETE FROM requisition_headcount_overrides");
        jdbc.update("DELETE FROM headcount_plans");
        jdbc.update("DELETE FROM recruitment_requisitions");
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("UPDATE user_accounts SET department_id = NULL, admin_locked_at = NULL, admin_lock_reason = NULL, admin_locked_by = NULL");
        jdbc.update("UPDATE departments SET parent_id = NULL");
        jdbc.update("DELETE FROM departments");
        jdbc.update("DELETE FROM positions");
        jdbc.update("DELETE FROM user_accounts");
        bootstrap.run(new DefaultApplicationArguments());
        UUID adminId = jdbc.queryForObject("SELECT id FROM user_accounts WHERE email = 'admin@example.test'", UUID.class);
        fixturePasswordHash = accounts.findById(adminId).orElseThrow().getPasswordHash();
        hrId = account("hr@example.test", "Nguyễn Thị Hoa", Role.HR_MANAGER);
        headId = account("head@example.test", "Trưởng IT", Role.HIRING_MANAGER);
        UUID salesHeadId = account("sales@example.test", "Trưởng Kinh doanh", Role.HIRING_MANAGER);
        approverA = account("a@example.test", "Giám đốc A", Role.APPROVER);
        account("b@example.test", "Giám đốc B", Role.APPROVER);
        hrToken = token("hr@example.test");
        headToken = token("head@example.test");
        salesToken = token("sales@example.test");
        itId = department("IT", headId);
        salesId = department("SALES", salesHeadId);
        positionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO positions (id, code, name, level, salary_min, salary_max, active, created_at, updated_at)
                VALUES (?, 'DEV', 'Lập trình viên', 'Junior', 15000000, 25000000, TRUE, ?, ?)
                """, positionId, Timestamp.from(START), Timestamp.from(START));
        // Level 1 for everyone, level 2 (a named director) above 20M, level 3 (any other director) above 50M.
        flowId = flow(itId, step(1, null, null, "HR_MANAGER"), step(2, 20_000_000L, approverA, null),
                step(3, 50_000_000L, null, "APPROVER"));
    }

    @Test
    void theHeadSubmitsACompleteDraftAndTheRoundIsRecordedWithItsChain() throws Exception {
        UUID id = draft(itId, 20_000_000L, 22_000_000L, null, headToken);
        String updatedAt = column(id, "updated_at::text");
        clock.set(START.plusSeconds(90));

        var response = submit(id, Map.of("comment", "Cần gấp cho dự án mới"), headToken);
        JsonNode body = expect(response, 200);
        assertThat(body.path("status").asText()).isEqualTo("PENDING_APPROVAL");
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(column(id, "updated_at::text")).isEqualTo(updatedAt);
        assertThat(column(id, "status")).isEqualTo("PENDING_APPROVAL");

        Map<String, Object> event = jdbc.queryForMap("SELECT * FROM requisition_workflow_events WHERE requisition_id = ?", id);
        assertThat(event).containsEntry("revision", 1).containsEntry("event_type", "SUBMITTED")
                .containsEntry("status_before", "DRAFT").containsEntry("status_after", "PENDING_APPROVAL")
                .containsEntry("round_number", 1).containsEntry("flow_id", flowId).containsEntry("flow_version", 1)
                .containsEntry("level_count", 2).containsEntry("salary_basis", 22_000_000L)
                .containsEntry("actor_id", headId).containsEntry("comment", "Cần gấp cho dự án mới");
        assertThat(event.get("level_number")).isNull();
        assertThat(((Timestamp) event.get("occurred_at")).toInstant()).isEqualTo(START.plusSeconds(90));

        JsonNode pending = expect(get("/api/v1/requisitions?status=PENDING_APPROVAL", headToken), 200);
        assertThat(pending.path("items").get(0).path("id").asText()).isEqualTo(id.toString());
        assertThat(expect(get("/api/v1/requisitions?status=DRAFT", headToken), 200).path("totalElements").asLong())
                .isZero();
    }

    @Test
    void theProposedMaximumSalaryDecidesTheLevels() throws Exception {
        UUID atThreshold = draft(itId, 20_000_000L, 20_000_000L, null, headToken);
        UUID overBoth = draft(itId, 40_000_000L, 60_000_000L, "Chuyên gia hiếm", headToken);

        expect(submit(atThreshold, null, headToken), 200);
        expect(submit(overBoth, Map.of("comment", "   "), headToken), 200);

        assertThat(levelCount(atThreshold)).isEqualTo(1);
        assertThat(levelCount(overBoth)).isEqualTo(3);
        // A blank note is no note.
        assertThat(jdbc.queryForObject("SELECT comment FROM requisition_workflow_events WHERE requisition_id = ?",
                String.class, overBoth)).isNull();
    }

    // A note of only non-breaking spaces (PostgreSQL counts them as spaces, so the history CHECK refused it: 500) or
    // only zero-width characters (stored, but showing nothing) has no visible character: it is no note either.
    @Test
    void aNoteWithoutAnyVisibleCharacterIsNoNote() throws Exception {
        String noBreakSpace = String.valueOf((char) 0x00A0);
        String zeroWidthSpace = new String(Character.toChars(0x200B));
        String mixed = codePoints(0x2007, 0x202F, '\n', 0x0085, 0x180E, 0xFEFF, 0x2060, '\t');
        for (String note : new String[]{noBreakSpace, zeroWidthSpace, mixed}) {
            UUID id = draft(itId, 20_000_000L, 22_000_000L, null, headToken);
            assertThat(expect(submit(id, Map.of("comment", note), headToken), 200).path("status").asText())
                    .isEqualTo("PENDING_APPROVAL");
            assertThat(jdbc.queryForObject("SELECT comment FROM requisition_workflow_events WHERE requisition_id = ?",
                    String.class, id)).as(note.codePoints().boxed().toList().toString()).isNull();
        }

        // A note with a visible character is kept exactly as sent, its line breaks and spaces included.
        String visible = noBreakSpace + "Cần gấp\ncho dự án" + zeroWidthSpace;
        UUID id = draft(itId, 20_000_000L, 22_000_000L, null, headToken);
        expect(submit(id, Map.of("comment", visible), headToken), 200);
        assertThat(jdbc.queryForObject("SELECT comment FROM requisition_workflow_events WHERE requisition_id = ?",
                String.class, id)).isEqualTo(visible);
    }

    @Test
    void aRoundKeepsTheVersionItStartedWithWhenTheConfigurationChanges() throws Exception {
        UUID first = draft(itId, 20_000_000L, 22_000_000L, null, headToken);
        expect(submit(first, null, headToken), 200);
        publish(flowId, 2, step(1, null, null, "APPROVER"));
        UUID second = draft(itId, 20_000_000L, 22_000_000L, null, headToken);
        expect(submit(second, null, headToken), 200);

        assertThat(jdbc.queryForObject("SELECT flow_version FROM requisition_workflow_events WHERE requisition_id = ?",
                Integer.class, first)).isEqualTo(1);
        assertThat(levelCount(first)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT flow_version FROM requisition_workflow_events WHERE requisition_id = ?",
                Integer.class, second)).isEqualTo(2);
        assertThat(levelCount(second)).isEqualTo(1);
    }

    @Test
    void anIncompleteDraftNamesEveryMissingFieldAndStaysADraft() throws Exception {
        UUID id = UUID.fromString(expect(request("POST", "/api/v1/requisitions", json.writeValueAsString(Map.of(
                "positionId", positionId, "departmentId", itId, "headcount", 1, "reason", "NEW_HEADCOUNT")), headToken),
                201).path("id").asText());

        JsonNode error = expect(submit(id, null, headToken), 400);
        assertThat(error.path("code").asText()).isEqualTo("REQUISITION_INCOMPLETE");
        assertThat(error.path("fieldErrors").propertyNames()).containsExactlyInAnyOrder("proposedSalaryMin",
                "proposedSalaryMax", "neededBy", "jobDescription", "candidateRequirements");
        assertNothingSubmitted(id);
    }

    @Test
    void theChecksOfASaveRunAgainWithTheirCurrentValues() throws Exception {
        UUID id = draft(itId, 20_000_000L, 22_000_000L, null, headToken);

        // HR lowers the standard band: the proposal is now above it and needs a justification.
        jdbc.update("UPDATE positions SET salary_max = 21000000 WHERE id = ?", positionId);
        assertThat(expect(submit(id, null, headToken), 400).path("code").asText())
                .isEqualTo("SALARY_JUSTIFICATION_REQUIRED");
        jdbc.update("UPDATE positions SET salary_max = 25000000, active = FALSE WHERE id = ?", positionId);
        assertThat(expect(submit(id, null, headToken), 400).path("code").asText())
                .isEqualTo("REQUISITION_POSITION_INACTIVE");
        jdbc.update("UPDATE positions SET active = TRUE WHERE id = ?", positionId);
        jdbc.update("UPDATE departments SET active = FALSE WHERE id = ?", itId);
        assertThat(expect(submit(id, null, headToken), 400).path("code").asText())
                .isEqualTo("REQUISITION_DEPARTMENT_INACTIVE");
        jdbc.update("UPDATE departments SET active = TRUE WHERE id = ?", itId);
        // The needed-by date (1 Dec 2026) has passed.
        clock.set(Instant.parse("2026-12-02T03:00:00Z"));
        String lateToken = token("head@example.test");
        JsonNode late = expect(submit(id, null, lateToken), 400);
        assertThat(late.path("code").asText()).isEqualTo("NEEDED_BY_IN_PAST");
        assertThat(late.path("fieldErrors").has("neededBy")).isTrue();
        assertNothingSubmitted(id);
    }

    @Test
    void aDepartmentWithoutAFlowOrALevelWithoutAnApproverCannotSubmit() throws Exception {
        UUID sales = draft(salesId, 20_000_000L, 22_000_000L, null, salesToken);
        assertThat(expect(submit(sales, null, salesToken), 409).path("code").asText())
                .isEqualTo("APPROVAL_FLOW_NOT_CONFIGURED");
        assertNothingSubmitted(sales);

        // HR is the only HR manager: submitting it, HR could not also decide level 1.
        UUID id = draft(itId, 20_000_000L, 22_000_000L, null, headToken);
        JsonNode error = expect(submit(id, null, hrToken), 409);
        assertThat(error.path("code").asText()).isEqualTo("APPROVAL_LEVEL_UNAVAILABLE");
        assertThat(error.path("message").asText()).contains("cấp 1 (Trưởng phòng Nhân sự)");
        assertNothingSubmitted(id);
    }

    @Test
    void aSubmittedRequisitionIsNeitherSubmittedAgainNorEdited() throws Exception {
        UUID id = draft(itId, 20_000_000L, 22_000_000L, null, headToken);
        expect(submit(id, null, headToken), 200);

        assertThat(expect(submit(id, null, headToken), 409).path("code").asText())
                .isEqualTo("REQUISITION_NOT_SUBMITTABLE");
        assertThat(expect(request("PUT", BASE + id, json.writeValueAsString(content(itId, 20_000_000L, 22_000_000L,
                null)), headToken), 409).path("code").asText()).isEqualTo("REQUISITION_NOT_DRAFT");
        assertThat(events(id)).isOne();
    }

    @Test
    void onlyWritersWhoMayAlsoReadTheRequisitionSubmitIt() throws Exception {
        UUID id = draft(itId, 20_000_000L, 22_000_000L, null, headToken);

        forbidden(submit(id, null, salesToken));
        assertThat(expect(submit(UUID.randomUUID(), null, headToken), 404).path("code").asText())
                .isEqualTo("REQUISITION_NOT_FOUND");
        assertThat(expect(request("POST", BASE + id + "/submit", "{\"comment\":\"x\",\"status\":\"APPROVED\"}",
                headToken), 400).path("code").asText()).isEqualTo("INVALID_JSON");
        JsonNode tooLong = expect(submit(id, Map.of("comment", "a".repeat(2_001)), headToken), 400);
        assertThat(tooLong.path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(tooLong.path("fieldErrors").has("comment")).isTrue();
        assertThat(request("POST", BASE + id + "/submit", null, null).statusCode()).isEqualTo(401);

        // The answer shows the whole requisition, so WRITE alone is not enough, not even to learn whether an id exists.
        jdbc.update("DELETE FROM role_permissions WHERE role_code = 'HIRING_MANAGER' AND permission_code = 'REQUISITIONS_READ_SCOPED'");
        forbidden(submit(UUID.randomUUID(), null, headToken));
        forbidden(submit(id, null, headToken));
        assertNothingSubmitted(id);
    }

    // The scope is checked before the requisition's state, so an outsider learns nothing about it.
    @Test
    void outsideTheScopeEveryStateAnswers403() throws Exception {
        UUID incomplete = UUID.fromString(expect(request("POST", "/api/v1/requisitions", json.writeValueAsString(Map.of(
                "positionId", positionId, "departmentId", itId, "headcount", 1, "reason", "NEW_HEADCOUNT")), headToken),
                201).path("id").asText());
        UUID pending = draft(itId, 20_000_000L, 22_000_000L, null, headToken);
        expect(submit(pending, null, headToken), 200);

        forbidden(submit(incomplete, null, salesToken));
        forbidden(submit(pending, null, salesToken));
        assertNothingSubmitted(incomplete);
        assertThat(events(pending)).isOne();
    }

    // Writing everywhere does not widen reading: a writer who reads only their own departments cannot submit another
    // department's requisition.
    @Test
    void aWriterForAllDepartmentsStillNeedsToReadTheRequisition() throws Exception {
        UUID sales = draft(salesId, 20_000_000L, 22_000_000L, null, salesToken);
        jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('HIRING_MANAGER', 'REQUISITIONS_WRITE_ALL')");
        try {
            forbidden(submit(sales, null, headToken));
        } finally {
            jdbc.update("DELETE FROM role_permissions WHERE role_code = 'HIRING_MANAGER' AND permission_code = 'REQUISITIONS_WRITE_ALL'");
        }
        assertNothingSubmitted(sales);
    }

    @Test
    void aCopyOfASubmittedRequisitionIsADraftWithoutHistory() throws Exception {
        UUID id = draft(itId, 20_000_000L, 22_000_000L, null, headToken);
        expect(submit(id, Map.of("comment", "Lần đầu"), headToken), 200);
        var before = jdbc.queryForList("SELECT * FROM requisition_workflow_events ORDER BY revision");

        JsonNode copy = expect(request("POST", BASE + id + "/copy", null, headToken), 201);
        assertThat(copy.path("status").asText()).isEqualTo("DRAFT");
        assertThat(events(UUID.fromString(copy.path("id").asText()))).isZero();
        assertThat(jdbc.queryForList("SELECT * FROM requisition_workflow_events ORDER BY revision")).isEqualTo(before);
    }

    private static String codePoints(int... characters) {
        return new String(characters, 0, characters.length);
    }

    private void assertNothingSubmitted(UUID id) {
        assertThat(column(id, "status")).isEqualTo("DRAFT");
        assertThat(events(id)).isZero();
    }

    private int events(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM requisition_workflow_events WHERE requisition_id = ?",
                Integer.class, id);
    }

    private int levelCount(UUID id) {
        return jdbc.queryForObject("SELECT level_count FROM requisition_workflow_events WHERE requisition_id = ?",
                Integer.class, id);
    }

    private String column(UUID id, String expression) {
        return jdbc.queryForObject("SELECT " + expression + " FROM recruitment_requisitions WHERE id = ?", String.class, id);
    }

    // A complete draft: both salaries, needed by 1 Dec 2026, job description and candidate requirements.
    private UUID draft(UUID department, long min, long max, String justification, String token) throws Exception {
        return UUID.fromString(expect(request("POST", "/api/v1/requisitions",
                json.writeValueAsString(content(department, min, max, justification)), token), 201).path("id").asText());
    }

    private Map<String, Object> content(UUID department, long min, long max, String justification) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("positionId", positionId);
        body.put("departmentId", department);
        body.put("headcount", 1);
        body.put("reason", "NEW_HEADCOUNT");
        body.put("proposedSalaryMin", min);
        body.put("proposedSalaryMax", max);
        if (justification != null) {
            body.put("salaryJustification", justification);
        }
        body.put("neededBy", "2026-12-01");
        body.put("jobDescription", "Phát triển dịch vụ tuyển dụng.");
        body.put("candidateRequirements", "Ba năm kinh nghiệm Java.");
        return body;
    }

    private record Step(int position, Long threshold, UUID user, String role) { }

    private static Step step(int position, Long threshold, UUID user, String role) {
        return new Step(position, threshold, user, role);
    }

    private UUID flow(UUID department, Step... steps) {
        UUID id = UUID.randomUUID();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.update("INSERT INTO approval_flows (id, department_id, current_version) VALUES (?, ?, 1)", id, department);
            version(id, 1, steps);
        });
        return id;
    }

    private void publish(UUID flow, int version, Step... steps) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            version(flow, version, steps);
            jdbc.update("UPDATE approval_flows SET current_version = ? WHERE id = ?", version, flow);
        });
    }

    private void version(UUID flow, int version, Step... steps) {
        jdbc.update("INSERT INTO approval_flow_versions (flow_id, version, name, created_by, created_at) VALUES (?, ?, ?, ?, ?)",
                flow, version, "Duyệt IT " + version, hrId, OffsetDateTime.parse("2026-10-11T00:00:00Z"));
        for (Step step : steps) {
            jdbc.update("""
                    INSERT INTO approval_flow_steps (flow_id, version, position, salary_threshold, approver_user_id, approver_role)
                    VALUES (?, ?, ?, ?, ?, ?)""", flow, version, step.position(), step.threshold(), step.user(), step.role());
        }
        jdbc.update("UPDATE approval_flow_versions SET sealed = TRUE WHERE flow_id = ? AND version = ?", flow, version);
    }

    private UUID account(String email, String name, Role... roles) {
        return accounts.saveAndFlush(new Account(email, name, fixturePasswordHash, Set.of(roles), START)).getId();
    }

    private UUID department(String code, UUID manager) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO departments (id, code, name, manager_user_id, active, created_at) VALUES (?, ?, ?, ?, TRUE, ?)",
                id, code, "Phòng " + code, manager, Timestamp.from(START));
        return id;
    }

    private String token(String email) throws Exception {
        return expect(request("POST", "/api/v1/auth/login",
                json.writeValueAsString(Map.of("email", email, "password", PASSWORD)), null), 200)
                .path("accessToken").asText();
    }

    private HttpResponse<String> submit(UUID requisition, Map<String, Object> body, String token) throws Exception {
        return request("POST", BASE + requisition + "/submit", body == null ? null : json.writeValueAsString(body), token);
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return request("GET", path, null, token);
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

    private void forbidden(HttpResponse<String> response) {
        assertThat(expect(response, 403).path("code").asText()).isEqualTo("FORBIDDEN");
    }
}
