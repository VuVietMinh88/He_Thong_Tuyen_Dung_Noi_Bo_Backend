package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.account.BootstrapAdmin;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.requisition.RecruiterAssignmentAccess;
import vn.ttcs.recruitment.requisition.RequisitionRecruiterRole;
import vn.ttcs.recruitment.requisition.RequisitionStatus;

import javax.sql.DataSource;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static vn.ttcs.recruitment.auth.ApprovalTestSupport.step;

// Task 285 (story S3-06): "Recruiter chỉ nhìn thấy ứng viên của vị trí được giao". There is no candidate module yet, so
// the rule is applied where recruiter data exists: a holder of REQUISITIONS_READ_SCOPED who is the primary or a
// supporting recruiter of a requisition now (RecruiterAssignmentAccess) reads that requisition and its read-only
// parts, and loses it the moment a handover or a removal commits. Every write keeps the department rule.
// Fixture: IT (head) with a flow whose only level is HR_MANAGER, SALES (another head), HR, recruiters r1, r2, r3
// (RECRUITER only, managing nothing) and an APPROVER.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RequisitionRecruiterAccessIntegrationTest {
    private static final String BASE = "/api/v1/requisitions";
    // 10:00 on 12 Oct 2026 in Vietnam. Access tokens last 15 minutes.
    private static final Instant START = Instant.parse("2026-10-12T03:00:00Z");
    // The reads built on GET /requisitions/{id} ("" is the detail itself).
    private static final List<String> READS = List.of("", "/assignment", "/assignment-history", "/approvals");

    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private AccountRepository accounts;
    @Autowired private BootstrapAdmin bootstrap;
    @Autowired private AuthIntegrationTest.MutableClock clock;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private RecruiterAssignmentAccess assignments;

    private RequisitionStatusTestSupport status;
    private JobPostingTestSupport postings;
    private ApprovalTestSupport api;
    private List<Map<String, Object>> permissionSeed;
    private String passwordHash;
    private UUID adminId;
    private UUID hrId;
    private String hrToken;
    private String headToken;
    private UUID r1;
    private UUID r2;
    private UUID r3;
    private String r1Token;
    private String r2Token;
    private String r3Token;
    private UUID it;
    private UUID position;

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
        status = new RequisitionStatusTestSupport(jdbc, json, transactions,
                Integer.parseInt(environment.getRequiredProperty("local.server.port")));
        postings = status.postings;
        api = status.api;
        status.reset();
        bootstrap.run(new DefaultApplicationArguments());
        adminId = jdbc.queryForObject("SELECT id FROM user_accounts WHERE email = 'admin@example.test'", UUID.class);
        passwordHash = accounts.findById(adminId).orElseThrow().getPasswordHash();
        hrId = account("hr@example.test", "Nguyễn Thị Hoa", Role.HR_MANAGER);
        UUID headId = account("head@example.test", "Trưởng phòng IT", Role.HIRING_MANAGER);
        UUID salesHeadId = account("sales@example.test", "Trưởng phòng Kinh doanh", Role.HIRING_MANAGER);
        r1 = account("r1@example.test", "Recruiter 1", Role.RECRUITER);
        r2 = account("r2@example.test", "Recruiter 2", Role.RECRUITER);
        r3 = account("r3@example.test", "Recruiter 3", Role.RECRUITER);
        account("approver@example.test", "Người duyệt", Role.APPROVER);
        hrToken = api.token("hr@example.test");
        headToken = api.token("head@example.test");
        r1Token = api.token("r1@example.test");
        r2Token = api.token("r2@example.test");
        r3Token = api.token("r3@example.test");
        it = api.department("IT", headId, null, START);
        api.department("SALES", salesHeadId, null, START);
        position = api.position(START);
        api.flow(it, hrId, START, step(1, null, null, "HR_MANAGER"));
    }

    @AfterEach
    void restorePermissionSeed() {
        if (permissionSeed == null) { return; }
        jdbc.update("DELETE FROM role_permissions");
        jdbc.batchUpdate("INSERT INTO role_permissions (role_code, permission_code) VALUES (?, ?)", permissionSeed
                .stream().map(row -> new Object[] {row.get("role_code"), row.get("permission_code")}).toList());
    }

    // The requisition was submitted by the IT head and waits for HR at level 1 (PENDING_APPROVAL is assignable), so its
    // progress has a real waiting level. r1 is its primary, or a supporting recruiter next to r2.
    @ParameterizedTest
    @EnumSource(RequisitionRecruiterRole.class)
    void assignedRecruitersReadTheRequisitionAndItsReadOnlyParts(RequisitionRecruiterRole role) throws Exception {
        UUID id = api.submitted(position, it, 22_000_000L, headToken, headToken);
        team(id, role);

        var detail = read(id, "", r1Token);
        api.expect(detail, 200);
        postings.noStore(detail);
        assertThat(json.readTree(detail.body())).isEqualTo(api.expect(read(id, "", hrToken), 200));

        JsonNode assignment = api.expect(read(id, "/assignment", r1Token), 200);
        assertThat(teamOf(assignment)).contains(r1 + ":" + role);
        // Eligibility belongs to those who may assign (task 284), not to a reader through the assignment.
        recruiterNodes(assignment).forEach(node -> assertThat(node.has("eligible")).isFalse());
        JsonNode history = api.expect(read(id, "/assignment-history", r1Token), 200);
        assertThat(history.size()).isEqualTo(role == RequisitionRecruiterRole.PRIMARY ? 1 : 2);

        JsonNode progress = api.expect(read(id, "/approvals", r1Token), 200);
        assertThat(progress.path("status").asText()).isEqualTo("PENDING_APPROVAL");
        assertThat(progress.path("currentLevel").asInt()).isEqualTo(1);
        assertThat(progress.path("waitingOn").path("kind").asText()).isEqualTo("APPROVAL_LEVEL");
        // Reading through the assignment never lets a recruiter decide.
        assertThat(progress.path("actions").path("canDecide").asBoolean()).isFalse();

        JsonNode page = list(r1Token);
        assertThat(ids(page)).containsExactly(id);
        assertThat(page.path("totalElements").asLong()).isOne();
        // Not widened: the inbox follows ApproverRule, the HR exceptions and the plan numbers follow the department.
        JsonNode inbox = api.expect(api.request("GET", BASE + "/approval-inbox", null, r1Token), 200);
        assertThat(inbox.path("items").isEmpty()).isTrue();
        assertThat(inbox.path("totalElements").asLong()).isZero();
        api.forbidden(read(id, "/headcount-overrides", r1Token));
        api.forbidden(api.request("GET", "/api/v1/headcount-plans/remaining?departmentId=" + it + "&year=2026", null,
                r1Token));

        // A recruiter who is not on the team reads nothing of it.
        for (String suffix : List.of("", "/assignment", "/assignment-history", "/approvals", "/headcount-overrides")) {
            api.forbidden(read(id, suffix, r3Token));
        }
        assertThat(ids(list(r3Token))).isEmpty();
    }

    // Being assigned opens reading only. PUT on an approved requisition and copy with an inactive position stop between
    // the saved-row (or source) check and the department check of the body, so they show a widened check as 409 or 400.
    @Test
    void writesStayWithTheDepartment() throws Exception {
        UUID ready = api.draft(position, it, 22_000_000L, headToken);
        UUID approved = postings.approvedRequisition(position, it, headToken, hrToken);
        UUID pending = api.submitted(position, it, 22_000_000L, headToken, headToken);
        for (UUID id : List.of(ready, approved, pending)) {
            assign(id, r1, "PRIMARY");
        }
        Map<String, List<Map<String, Object>>> before = state();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("positionId", position);
        body.put("departmentId", it);
        body.put("headcount", 3);
        body.put("reason", "NEW_HEADCOUNT");

        api.forbidden(api.request("PUT", BASE + "/" + ready, json.writeValueAsString(body), r1Token));
        // A widened check of the saved row would answer 409 REQUISITION_NOT_DRAFT here.
        api.forbidden(api.request("PUT", BASE + "/" + approved, json.writeValueAsString(body), r1Token));
        api.forbidden(api.request("POST", BASE + "/" + ready + "/submit", null, r1Token));
        api.forbidden(api.request("POST", BASE + "/" + ready + "/copy", null, r1Token));
        // A widened decision would answer 409 REQUISITION_NOT_PENDING_APPROVAL (the requisition is approved).
        api.forbidden(api.decide(approved, "REJECT", "Không phù hợp", 2, r1Token));
        api.forbidden(api.decide(pending, "REJECT", "Không phù hợp", 1, r1Token));
        api.forbidden(status.change(approved, "PAUSED", "Tạm dừng", 2, r1Token));
        api.forbidden(api.request("POST", BASE + "/" + approved + "/assign",
                json.writeValueAsString(Map.of("recruiterId", r3)), r1Token));
        assertThat(state()).isEqualTo(before);

        // A widened check of the source would answer 400 REQUISITION_POSITION_INACTIVE here.
        jdbc.update("UPDATE positions SET active = FALSE WHERE id = ?", position);
        api.forbidden(api.request("POST", BASE + "/" + ready + "/copy", null, r1Token));
        assertThat(state()).isEqualTo(before);
    }

    // "Kể cả sau bàn giao": the access follows the current team. The requisition is approved and has no posting, so
    // the source list of job postings shows the same switch: requisition reads and postings use one rule.
    @ParameterizedTest
    @EnumSource(RequisitionRecruiterRole.class)
    void aHandoverOrRemovalTakesTheAccessAwayAtOnce(RequisitionRecruiterRole role) throws Exception {
        UUID id = postings.approvedRequisition(position, it, headToken, hrToken);
        team(id, role);
        for (String suffix : READS) {
            api.expect(read(id, suffix, r1Token), 200);
        }
        assertThat(ids(list(r1Token))).containsExactly(id);
        assertThat(sources(r1Token)).containsExactly(id);

        if (role == RequisitionRecruiterRole.PRIMARY) {
            assign(id, r2, "PRIMARY");
        } else {
            api.expect(api.request("POST", BASE + "/" + id + "/unassign",
                    json.writeValueAsString(Map.of("recruiterId", r1)), hrToken), 200);
        }
        for (String suffix : READS) {
            api.forbidden(read(id, suffix, r1Token));
        }
        assertThat(ids(list(r1Token))).isEmpty();
        assertThat(sources(r1Token)).isEmpty();
        assertThat(api.expect(read(id, "", r2Token), 200).path("id").asText()).isEqualTo(id.toString());
        assertThat(ids(list(r2Token))).containsExactly(id);

        // Promoting a supporting recruiter keeps the access; the old primary loses it.
        assign(id, r1, "SUPPORTING");
        assign(id, r1, "PRIMARY");
        api.expect(read(id, "", r1Token), 200);
        assertThat(ids(list(r1Token))).containsExactly(id);
        api.forbidden(read(id, "", r2Token));
    }

    // A team change in progress holds the requisition row (FOR NO KEY UPDATE, the lock every team writer takes). Reads
    // take no lock: they answer at once with the committed team (the 20 s request timeout is the guard), and see the
    // change from the first request after its commit.
    @Test
    void aReadDoesNotWaitForATeamChangeInProgress() throws Exception {
        UUID id = api.draft(position, it, 22_000_000L, headToken);
        assign(id, r2, "PRIMARY");
        assign(id, r1, "SUPPORTING");
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (var statement = connection.prepareStatement(
                        "SELECT id FROM recruitment_requisitions WHERE id = ? FOR NO KEY UPDATE")) {
                    statement.setObject(1, id);
                    try (var row = statement.executeQuery()) { assertThat(row.next()).isTrue(); }
                }
                // Only a SUPPORTING row is removed with SQL: a team without a primary makes /assignment answer 500.
                try (var statement = connection.prepareStatement(
                        "DELETE FROM requisition_recruiters WHERE requisition_id = ? AND recruiter_id = ?")) {
                    statement.setObject(1, id);
                    statement.setObject(2, r1);
                    assertThat(statement.executeUpdate()).isOne();
                }
                api.expect(read(id, "", r1Token), 200);
                assertThat(ids(list(r1Token))).containsExactly(id);
                connection.commit();
            } finally {
                connection.rollback();
            }
        }
        api.forbidden(read(id, "", r1Token));
        assertThat(ids(list(r1Token))).isEmpty();
    }

    // Decided for every status (BA/PO question 19.3): an assigned recruiter reads the requisition before approval,
    // during it and after it ended. A new status does not compile until someone decides.
    @ParameterizedTest
    @EnumSource(RequisitionStatus.class)
    void assignedRecruitersReadInEveryStatus(RequisitionStatus requisitionStatus) throws Exception {
        boolean readable = switch (requisitionStatus) {
            case DRAFT, PENDING_APPROVAL, RETURNED, APPROVED, REJECTED, PAUSED, FILLED, CANCELLED -> true;
        };
        UUID id = api.draft(position, it, 22_000_000L, headToken);
        assign(id, r1, "PRIMARY");
        // The table has no trigger, so the status is set with SQL.
        assertThat(jdbc.update("UPDATE recruitment_requisitions SET status = ? WHERE id = ?", requisitionStatus.name(),
                id)).isOne();

        var detail = read(id, "", r1Token);
        if (readable) {
            assertThat(api.expect(detail, 200).path("status").asText()).isEqualTo(requisitionStatus.name());
        } else {
            api.forbidden(detail);
        }
        assertThat(ids(list(r1Token)).contains(id)).isEqualTo(readable);
    }

    // Matrix question 2: the permission codes decide, not the role names. The team row stays until HR hands over.
    @Test
    void permissionsNotRoleNamesDecide() throws Exception {
        UUID id = api.draft(position, it, 22_000_000L, headToken);
        assign(id, r1, "PRIMARY");

        // HIRING_MANAGER also holds REQUISITIONS_READ_SCOPED, and r1 still manages no department.
        assertThat(jdbc.update("UPDATE user_roles SET role = 'HIRING_MANAGER' WHERE user_id = ? AND role = 'RECRUITER'",
                r1)).isOne();
        api.expect(read(id, "", r1Token), 200);
        assertThat(ids(list(r1Token))).containsExactly(id);

        // Without any READ permission of REQUISITIONS nothing is open, assigned or not.
        jdbc.update("DELETE FROM user_roles WHERE user_id = ?", r1);
        api.forbidden(read(id, "", r1Token));
        api.forbidden(api.request("GET", BASE, null, r1Token));
        assertThat(teamRows(id)).containsExactly(r1 + ":PRIMARY");
    }

    // A constructed state that makes the rule of the writes observable: HR keeps REQUISITIONS_WRITE_ALL but reads only
    // SCOPED and manages nothing, and is the primary recruiter of the requisition. Reading is open through the
    // assignment; the HR writes keep the department rule of reading (requireInReadScope) and write nothing.
    @Test
    void writesKeepTheDepartmentRuleEvenForAnAssignedWriteAllHolder() throws Exception {
        UUID id = postings.requisition(position, it, adminId, RequisitionStatus.APPROVED, START);
        jdbc.update("DELETE FROM role_permissions WHERE role_code = 'HR_MANAGER' "
                + "AND permission_code = 'REQUISITIONS_READ_ALL'");
        jdbc.update("INSERT INTO role_permissions (role_code, permission_code) "
                + "VALUES ('HR_MANAGER', 'REQUISITIONS_READ_SCOPED')");
        postings.assign(id, hrId, RequisitionRecruiterRole.PRIMARY, adminId, START);
        Map<String, List<Map<String, Object>>> before = state();

        assertThat(api.expect(read(id, "", hrToken), 200).path("status").asText()).isEqualTo("APPROVED");
        api.forbidden(status.change(id, "PAUSED", "Chờ ngân sách", 0, hrToken));
        api.forbidden(api.request("POST", BASE + "/" + id + "/assign",
                json.writeValueAsString(Map.of("recruiterId", r3)), hrToken));
        assertThat(state()).isEqualTo(before);
    }

    // The component reads in the caller's transaction (MANDATORY) and counts both roles.
    @Test
    void theRuleReadsInTheCallersTransaction() throws Exception {
        UUID id = api.draft(position, it, 22_000_000L, headToken);
        assign(id, r2, "PRIMARY");
        assign(id, r1, "SUPPORTING");

        assertThatThrownBy(() -> assignments.isAssigned(id, r2)).isInstanceOf(IllegalTransactionStateException.class);
        var template = new TransactionTemplate(transactions);
        template.setReadOnly(true);
        List<Boolean> answers = template.execute(transaction -> List.of(assignments.isAssigned(id, r2),
                assignments.isAssigned(id, r1), assignments.isAssigned(id, r3),
                assignments.isAssigned(UUID.randomUUID(), r2)));
        // Primary, supporting, not on the team, unknown requisition.
        assertThat(answers).containsExactly(true, true, false, false);
    }

    // PRIMARY: r1 alone. SUPPORTING: r2 primary, r1 supporting. Built through the API, the way HR builds it.
    private void team(UUID requisition, RequisitionRecruiterRole role) throws Exception {
        if (role == RequisitionRecruiterRole.PRIMARY) {
            assign(requisition, r1, "PRIMARY");
        } else {
            assign(requisition, r2, "PRIMARY");
            assign(requisition, r1, "SUPPORTING");
        }
    }

    private void assign(UUID requisition, UUID recruiter, String role) throws Exception {
        api.expect(api.request("POST", BASE + "/" + requisition + "/assign",
                json.writeValueAsString(Map.of("recruiterId", recruiter, "role", role)), hrToken), 200);
    }

    // suffix: "" for GET /requisitions/{id}, or "/assignment" and the other reads below it.
    private HttpResponse<String> read(UUID requisition, String suffix, String token) throws Exception {
        return api.request("GET", BASE + "/" + requisition + suffix, null, token);
    }

    private JsonNode list(String token) throws Exception {
        var response = api.request("GET", BASE, null, token);
        JsonNode page = api.expect(response, 200);
        postings.noStore(response);
        assertThat(page.path("totalElements").asLong()).isEqualTo(page.path("items").size());
        return page;
    }

    private static List<UUID> ids(JsonNode page) {
        List<UUID> result = new ArrayList<>();
        page.path("items").forEach(item -> result.add(UUID.fromString(item.path("id").asText())));
        return result;
    }

    // The requisitions the caller may make a posting from (GET /job-postings/source-requisitions).
    private List<UUID> sources(String token) throws Exception {
        List<UUID> result = new ArrayList<>();
        api.expect(postings.sources("", token), 200).path("items").forEach(item ->
                result.add(UUID.fromString(item.path("requisitionId").asText())));
        return result;
    }

    // "id:ROLE" for each recruiter of an assignment view.
    private static List<String> teamOf(JsonNode assignment) {
        List<String> result = new ArrayList<>();
        if (!assignment.path("primaryRecruiter").isNull()) {
            result.add(assignment.path("primaryRecruiter").path("recruiterId").asText() + ":PRIMARY");
        }
        assignment.path("supportingRecruiters").forEach(node ->
                result.add(node.path("recruiterId").asText() + ":SUPPORTING"));
        return result;
    }

    private static List<JsonNode> recruiterNodes(JsonNode assignment) {
        List<JsonNode> result = new ArrayList<>();
        result.add(assignment.path("primaryRecruiter"));
        assignment.path("supportingRecruiters").forEach(result::add);
        return result;
    }

    private List<String> teamRows(UUID requisition) {
        return jdbc.queryForList("SELECT recruiter_id::text || ':' || assignment_role FROM requisition_recruiters "
                + "WHERE requisition_id = ? ORDER BY assignment_role, recruiter_id", String.class, requisition);
    }

    // Every table a requisition write or a team change could touch.
    private Map<String, List<Map<String, Object>>> state() {
        Map<String, List<Map<String, Object>>> state = new LinkedHashMap<>();
        state.put("recruitment_requisitions", jdbc.queryForList("SELECT * FROM recruitment_requisitions ORDER BY id"));
        state.put("requisition_workflow_events",
                jdbc.queryForList("SELECT * FROM requisition_workflow_events ORDER BY id"));
        state.put("requisition_recruiters",
                jdbc.queryForList("SELECT * FROM requisition_recruiters ORDER BY requisition_id, recruiter_id"));
        state.put("requisition_recruiter_changes",
                jdbc.queryForList("SELECT * FROM requisition_recruiter_changes ORDER BY id"));
        state.put("requisition_headcount_overrides",
                jdbc.queryForList("SELECT * FROM requisition_headcount_overrides ORDER BY id"));
        state.put("job_postings", jdbc.queryForList("SELECT * FROM job_postings ORDER BY id"));
        return state;
    }

    private UUID account(String email, String name, Role role) {
        return accounts.saveAndFlush(new Account(email, name, passwordHash, Set.of(role), START)).getId();
    }
}
