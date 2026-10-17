package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.account.BootstrapAdmin;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.account.handover.AccountHandoverService;
import vn.ttcs.recruitment.requisition.RequisitionStatus;

import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static vn.ttcs.recruitment.auth.ApprovalTestSupport.step;

// Feedback item 10 (locking an account): GET /accounts/{id}/handover-items lists the work the account still holds,
// so the lock dialog can hand it over: requisitions it recruits for, departments it manages, approval steps naming it
// and approval rounds in progress that still wait for it.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AccountHandoverItemsIntegrationTest {
    // 10:00 on 16 Oct 2026 in Vietnam.
    private static final Instant START = Instant.parse("2026-10-16T03:00:00Z");
    private static final List<String> VIEW_FIELDS = List.of("userId", "fullName", "status", "requisitions",
            "managedDepartments", "approvalSteps", "pendingApprovals");
    private static final List<String> REQUISITION_FIELDS = List.of("requisitionId", "positionCode", "positionName",
            "departmentId", "departmentName", "status", "role", "headcount", "neededBy", "assignedAt");
    private static final List<String> PENDING_FIELDS = List.of("requisitionId", "positionCode", "positionName",
            "departmentId", "departmentName", "round", "level", "current", "flowId", "flowVersion", "waitingSince");

    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccountRepository accounts;
    @Autowired private BootstrapAdmin bootstrap;
    @Autowired private AuthIntegrationTest.MutableClock clock;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private AccountHandoverService service;

    private ApprovalTestSupport api;
    private List<Map<String, Object>> permissionSeed;
    private String passwordHash;
    private UUID adminId;
    private String adminToken;
    private String hrToken;
    private UUID targetId;
    private UUID otherId;
    private UUID headId;
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
        permissionSeed = jdbc.queryForList("SELECT role_code, permission_code FROM role_permissions");
        api = new ApprovalTestSupport(jdbc, json, transactions,
                Integer.parseInt(environment.getRequiredProperty("local.server.port")));
        api.reset();
        bootstrap.run(new DefaultApplicationArguments());
        adminId = jdbc.queryForObject("SELECT id FROM user_accounts WHERE email = 'admin@example.test'", UUID.class);
        passwordHash = accounts.findById(adminId).orElseThrow().getPasswordHash();
        adminToken = api.token("admin@example.test");
        account("hr@example.test", "Nguyễn Thị Hoa", Role.HR_MANAGER);
        hrToken = api.token("hr@example.test");
        targetId = account("target@example.test", "Nguyễn Văn A", Role.RECRUITER);
        otherId = account("other@example.test", "Trần Thị B", Role.RECRUITER);
        headId = account("head@example.test", "Trưởng phòng IT", Role.HIRING_MANAGER);
        itId = api.department("IT", headId, null, START);
        positionId = api.position(START);
    }

    @AfterEach
    void restorePermissionSeed() {
        if (permissionSeed == null) { return; }
        jdbc.update("DELETE FROM role_permissions");
        jdbc.batchUpdate("INSERT INTO role_permissions (role_code, permission_code) VALUES (?, ?)", permissionSeed.stream()
                .map(row -> new Object[] {row.get("role_code"), row.get("permission_code")}).toList());
    }

    @Test
    void anAccountThatHoldsNothingHasFourEmptyLists() throws Exception {
        var response = items(targetId, adminToken);
        JsonNode view = api.expect(response, 200);

        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(view.size()).isEqualTo(VIEW_FIELDS.size());
        VIEW_FIELDS.forEach(field -> assertThat(view.has(field)).as(field).isTrue());
        assertThat(view.path("userId").asText()).isEqualTo(targetId.toString());
        assertThat(view.path("fullName").asText()).isEqualTo("Nguyễn Văn A");
        assertThat(view.path("status").asText()).isEqualTo("ACTIVE");
        for (String list : List.of("requisitions", "managedDepartments", "approvalSteps", "pendingApprovals")) {
            assertThat(view.path(list).isArray()).as(list).isTrue();
            assertThat(view.path(list).isEmpty()).as(list).isTrue();
        }
    }

    @Test
    void listsTheRequisitionsTheAccountRecruitsForWhileTheirRecruitersMayStillChange() throws Exception {
        // The account is the primary recruiter of one requisition in each status and a supporting recruiter of
        // another, created one minute apart; "other" holds the remaining role of each team.
        List<UUID> primary = new ArrayList<>();
        List<UUID> supporting = new ArrayList<>();
        int minute = 0;
        for (RequisitionStatus status : RequisitionStatus.values()) {
            UUID asPrimary = requisition(itId, status, START.plusSeconds(60L * minute++));
            recruiter(asPrimary, targetId, "PRIMARY", START);
            recruiter(asPrimary, otherId, "SUPPORTING", START);
            UUID asSupporting = requisition(itId, status, START.plusSeconds(60L * minute++));
            recruiter(asSupporting, otherId, "PRIMARY", START);
            recruiter(asSupporting, targetId, "SUPPORTING", START.plusSeconds(30));
            // A new status makes this switch fail to compile until someone decides whether its recruiters may still
            // change (RequisitionRecruiterService.ASSIGNABLE_STATUSES): only those are handed over.
            boolean listed = switch (status) {
                case DRAFT, PENDING_APPROVAL, RETURNED, APPROVED, PAUSED -> true;
                case REJECTED, FILLED, CANCELLED -> false;
            };
            if (listed) {
                primary.addFirst(asPrimary);
                supporting.addFirst(asSupporting);
            }
        }
        // A requisition the account is not on, and one of the other recruiter only.
        UUID foreign = requisition(itId, RequisitionStatus.APPROVED, START.plusSeconds(3600));
        recruiter(foreign, otherId, "PRIMARY", START);
        requisition(itId, RequisitionStatus.APPROVED, START.plusSeconds(3660));

        JsonNode list = api.expect(items(targetId, adminToken), 200).path("requisitions");

        // PRIMARY before SUPPORTING, the newest first within each role.
        List<UUID> expected = new ArrayList<>(primary);
        expected.addAll(supporting);
        assertThat(ids(list, "requisitionId")).containsExactlyElementsOf(expected);
        assertThat(list.path(0).path("role").asText()).isEqualTo("PRIMARY");
        assertThat(list.path(5).path("role").asText()).isEqualTo("SUPPORTING");
        List<String> statuses = new ArrayList<>();
        list.forEach(item -> statuses.add(item.path("status").asText()));
        assertThat(statuses).containsOnly("DRAFT", "PENDING_APPROVAL", "RETURNED", "APPROVED", "PAUSED");

        JsonNode item = list.path(5);
        assertThat(item.size()).isEqualTo(REQUISITION_FIELDS.size());
        REQUISITION_FIELDS.forEach(field -> assertThat(item.has(field)).as(field).isTrue());
        assertThat(item.path("requisitionId").asText()).isEqualTo(supporting.getFirst().toString());
        assertThat(item.path("positionCode").asText()).isEqualTo("DEV");
        assertThat(item.path("positionName").asText()).isEqualTo("Lập trình viên");
        assertThat(item.path("departmentId").asText()).isEqualTo(itId.toString());
        assertThat(item.path("departmentName").asText()).isEqualTo("Phòng IT");
        assertThat(item.path("status").asText()).isEqualTo("PAUSED");
        assertThat(item.path("headcount").asInt()).isEqualTo(2);
        assertThat(item.path("neededBy").asText()).isEqualTo("2026-12-01");
        assertThat(Instant.parse(item.path("assignedAt").asText())).isEqualTo(START.plusSeconds(30));
    }

    @Test
    void listsTheDepartmentsTheAccountManagesDirectlyWhileTheyAreStillUsed() throws Exception {
        UUID sales = api.department("SALES", targetId, null, START);
        UUID ops = api.department("OPS", targetId, null, START);
        // Below departments the account manages, so in its requisition scope, but each managed by someone else.
        api.department("SALES-HN", otherId, sales, START);
        api.department("OPS-HN", headId, ops, START);
        // Both deactivated. OLD only has a filled requisition, so nothing is left to hand over. LEGACY still has an
        // approved one, which its manager still reads in scope and whose HIRING_MANAGER levels it still decides.
        UUID old = api.department("OLD", targetId, null, START);
        requisition(old, RequisitionStatus.FILLED, START);
        UUID legacy = api.department("LEGACY", targetId, null, START);
        requisition(legacy, RequisitionStatus.APPROVED, START);
        jdbc.update("UPDATE departments SET active = FALSE WHERE id IN (?, ?)", old, legacy);

        JsonNode list = api.expect(items(targetId, adminToken), 200).path("managedDepartments");

        assertThat(ids(list, "departmentId")).containsExactly(legacy, ops, sales);
        JsonNode first = list.path(0);
        assertThat(first.size()).isEqualTo(4);
        assertThat(first.path("code").asText()).isEqualTo("LEGACY");
        assertThat(first.path("name").asText()).isEqualTo("Phòng LEGACY");
        assertThat(first.path("active").isBoolean()).isTrue();
        assertThat(first.path("active").asBoolean()).isFalse();
        assertThat(list.path(1).path("code").asText()).isEqualTo("OPS");
        assertThat(list.path(1).path("active").asBoolean()).isTrue();
    }

    @Test
    void listsOnlyTheStepsOfCurrentFlowVersionsThatNameTheAccount() throws Exception {
        UUID sales = api.department("SALES", headId, null, START);
        UUID ops = api.department("OPS", headId, null, START);
        // IT: version 1 named the account, the current version 2 does not.
        UUID itFlow = api.flow(itId, adminId, START, step(1, null, targetId, null));
        api.publish(itFlow, 2, adminId, START.plusSeconds(60), step(1, null, null, "HR_MANAGER"));
        // SALES: the current version 1 names the account at level 2.
        UUID salesFlow = api.flow(sales, adminId, START, step(1, null, null, "HR_MANAGER"),
                step(2, 50_000_000L, targetId, null));
        // OPS: version 1 did not name it, the current version 2 names it at level 1.
        UUID opsFlow = api.flow(ops, adminId, START, step(1, null, otherId, null));
        api.publish(opsFlow, 2, adminId, START.plusSeconds(60), step(1, null, targetId, null),
                step(2, 30_000_000L, otherId, null));
        // CLOSED ("Phòng CLOSED" would come first) is no longer used: no submission takes its flow, and the flow
        // cannot be saved again.
        UUID closed = api.department("CLOSED", headId, null, START);
        api.flow(closed, adminId, START, step(1, null, targetId, null));
        jdbc.update("UPDATE departments SET active = FALSE WHERE id = ?", closed);

        JsonNode list = api.expect(items(targetId, adminToken), 200).path("approvalSteps");

        // Ordered by department name: "Phòng OPS" before "Phòng SALES".
        assertThat(ids(list, "flowId")).containsExactly(opsFlow, salesFlow);
        JsonNode first = list.path(0);
        assertThat(first.size()).isEqualTo(6);
        assertThat(first.path("departmentId").asText()).isEqualTo(ops.toString());
        assertThat(first.path("departmentName").asText()).isEqualTo("Phòng OPS");
        assertThat(first.path("flowName").asText()).isEqualTo("Duyệt tuyển dụng 2");
        assertThat(first.path("version").asInt()).isEqualTo(2);
        assertThat(first.path("position").asInt()).isEqualTo(1);
        JsonNode second = list.path(1);
        assertThat(second.path("version").asInt()).isEqualTo(1);
        assertThat(second.path("position").asInt()).isEqualTo(2);
        // The other recruiter is still named by OPS level 2 and no longer by the old version 1.
        JsonNode others = api.expect(items(otherId, adminToken), 200).path("approvalSteps");
        assertThat(ids(others, "flowId")).containsExactly(opsFlow);
        assertThat(others.path(0).path("position").asInt()).isEqualTo(2);
    }

    @Test
    void listsTheLevelsOfRoundsInProgressThatStillWaitForTheAccount() throws Exception {
        UUID sales = api.department("SALES", headId, null, START);
        // IT, version 1: "other" decides level 1, the account level 2.
        UUID itFlow = api.flow(itId, adminId, START, step(1, null, otherId, null), step(2, null, targetId, null));
        // Submitted with version 1 and approved at level 1: the round waits for the account now.
        UUID waitingNow = requisition(itId, RequisitionStatus.PENDING_APPROVAL, START);
        submitted(waitingNow, 1, 1, itFlow, 1, 2, "DRAFT", START.plusSeconds(60));
        decided(waitingNow, 2, 1, 1, "APPROVED", "PENDING_APPROVAL", otherId, START.plusSeconds(600));
        // Fully approved with version 1: nothing waits any more.
        UUID approved = requisition(itId, RequisitionStatus.APPROVED, START);
        submitted(approved, 1, 1, itFlow, 1, 2, "DRAFT", START.plusSeconds(60));
        decided(approved, 2, 1, 1, "APPROVED", "PENDING_APPROVAL", otherId, START.plusSeconds(120));
        decided(approved, 3, 1, 2, "APPROVED", "APPROVED", targetId, START.plusSeconds(180));
        // Returned in round 1 and submitted again with version 2, which no longer names the account.
        UUID resubmitted = requisition(itId, RequisitionStatus.PENDING_APPROVAL, START);
        submitted(resubmitted, 1, 1, itFlow, 1, 2, "DRAFT", START.plusSeconds(60));
        decided(resubmitted, 2, 1, 1, "INFO_REQUESTED", "RETURNED", otherId, START.plusSeconds(120));
        api.publish(itFlow, 2, adminId, START.plusSeconds(180), step(1, null, otherId, null));
        submitted(resubmitted, 3, 2, itFlow, 2, 1, "RETURNED", START.plusSeconds(240));
        // SALES, current version 1: the head decides level 1, the account level 2 above 50 million only.
        UUID salesFlow = api.flow(sales, adminId, START, step(1, null, headId, null),
                step(2, 50_000_000L, targetId, null));
        // Above the threshold (two levels): the account decides after the head.
        UUID later = requisition(sales, RequisitionStatus.PENDING_APPROVAL, START);
        submitted(later, 1, 1, salesFlow, 1, 2, "DRAFT", START.plusSeconds(300));
        // Below it (one level): the account's level does not apply.
        UUID belowThreshold = requisition(sales, RequisitionStatus.PENDING_APPROVAL, START);
        submitted(belowThreshold, 1, 1, salesFlow, 1, 1, "DRAFT", START.plusSeconds(300));

        JsonNode view = api.expect(items(targetId, adminToken), 200);

        // The current IT version no longer names the account, but the round submitted with version 1 still waits.
        assertThat(ids(view.path("approvalSteps"), "flowId")).containsExactly(salesFlow);
        JsonNode list = view.path("pendingApprovals");
        // Waiting longest first: SALES since its submission, IT since its level 1 was approved.
        assertThat(ids(list, "requisitionId")).containsExactly(later, waitingNow);
        JsonNode first = list.path(0);
        assertThat(first.size()).isEqualTo(PENDING_FIELDS.size());
        PENDING_FIELDS.forEach(field -> assertThat(first.has(field)).as(field).isTrue());
        assertThat(first.path("positionCode").asText()).isEqualTo("DEV");
        assertThat(first.path("positionName").asText()).isEqualTo("Lập trình viên");
        assertThat(first.path("departmentId").asText()).isEqualTo(sales.toString());
        assertThat(first.path("departmentName").asText()).isEqualTo("Phòng SALES");
        assertThat(first.path("round").asInt()).isEqualTo(1);
        assertThat(first.path("level").asInt()).isEqualTo(2);
        assertThat(first.path("current").asBoolean()).isFalse();
        assertThat(first.path("flowId").asText()).isEqualTo(salesFlow.toString());
        assertThat(first.path("flowVersion").asInt()).isEqualTo(1);
        assertThat(Instant.parse(first.path("waitingSince").asText())).isEqualTo(START.plusSeconds(300));
        JsonNode second = list.path(1);
        assertThat(second.path("departmentId").asText()).isEqualTo(itId.toString());
        assertThat(second.path("level").asInt()).isEqualTo(2);
        assertThat(second.path("current").asBoolean()).isTrue();
        assertThat(second.path("flowId").asText()).isEqualTo(itFlow.toString());
        assertThat(second.path("flowVersion").asInt()).isEqualTo(1);
        assertThat(Instant.parse(second.path("waitingSince").asText())).isEqualTo(START.plusSeconds(600));
        // "other" already decided level 1 of the IT round on version 1; round 2 of the resubmitted one waits for it.
        JsonNode others = api.expect(items(otherId, adminToken), 200).path("pendingApprovals");
        assertThat(ids(others, "requisitionId")).containsExactly(resubmitted);
        assertThat(others.path(0).path("round").asInt()).isEqualTo(2);
        assertThat(others.path(0).path("level").asInt()).isEqualTo(1);
        assertThat(others.path(0).path("current").asBoolean()).isTrue();
        assertThat(others.path(0).path("flowVersion").asInt()).isEqualTo(2);
    }

    @Test
    void anAccountAlreadyLockedStillShowsItsWork() throws Exception {
        UUID held = requisition(itId, RequisitionStatus.APPROVED, START);
        recruiter(held, targetId, "PRIMARY", START);
        UUID sales = api.department("SALES", targetId, null, START);
        api.expect(api.request("PUT", "/api/v1/accounts/" + targetId + "/lock",
                json.writeValueAsString(Map.of("reason", "Nghỉ việc")), adminToken), 200);

        JsonNode view = api.expect(items(targetId, adminToken), 200);

        assertThat(view.path("status").asText()).isEqualTo("ADMINISTRATIVELY_LOCKED");
        assertThat(ids(view.path("requisitions"), "requisitionId")).containsExactly(held);
        assertThat(ids(view.path("managedDepartments"), "departmentId")).containsExactly(sales);
    }

    @Test
    void anUnknownAccountIsNotFoundAndAnIdThatIsNoUuidIsRejected() throws Exception {
        var response = items(UUID.randomUUID(), adminToken);
        JsonNode error = api.expect(response, 404);
        assertThat(error.path("code").asText()).isEqualTo("ACCOUNT_NOT_FOUND");
        assertThat(error.path("message").asText()).isEqualTo("Không tìm thấy tài khoản.");
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");

        var invalid = api.request("GET", "/api/v1/accounts/HR01/handover-items", null, adminToken);
        assertThat(api.code(invalid, 400)).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void onlyAnAdministratorWhoMayAlsoReadEveryRequisitionSeesTheList() throws Exception {
        UUID held = requisition(itId, RequisitionStatus.APPROVED, START);
        recruiter(held, targetId, "PRIMARY", START);

        assertThat(api.code(items(targetId, null), 401)).isEqualTo("UNAUTHORIZED");
        // HR_MANAGER reads accounts and every requisition, but may not administer accounts.
        api.forbidden(items(targetId, hrToken));
        // Nor with the account write permission: the role ADMIN is required as well.
        jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('HR_MANAGER', 'USER_ADMIN_WRITE_ALL')");
        api.forbidden(items(targetId, hrToken));
        // The account itself cannot read what it holds here.
        api.forbidden(items(targetId, api.token("target@example.test")));

        // An administrator who can no longer read every requisition is refused on the next request.
        jdbc.update("DELETE FROM role_permissions WHERE role_code = 'ADMIN' AND permission_code = 'REQUISITIONS_READ_ALL'");
        api.forbidden(items(targetId, adminToken));
        jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('ADMIN', 'REQUISITIONS_READ_ALL')");
        jdbc.update("DELETE FROM role_permissions WHERE role_code = 'ADMIN' AND permission_code = 'USER_ADMIN_WRITE_ALL'");
        api.forbidden(items(targetId, adminToken));
        jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('ADMIN', 'USER_ADMIN_WRITE_ALL')");

        JsonNode view = api.expect(items(targetId, adminToken), 200);
        assertThat(ids(view.path("requisitions"), "requisitionId")).containsExactly(held);
    }

    // The matcher refuses each of these first; the service checks the token, the session, the role and the
    // permissions (read again) on its own as well.
    @Test
    void serviceChecksTheTokenSessionRoleAndPermissionsAgainWhenCalledDirectly() {
        UUID adminSession = sessionOf(adminId);
        Jwt admin = jwt(adminId, adminSession, START.plus(TokenService.ACCESS_TOKEN_TTL));
        assertThat(service.items(admin, targetId).userId()).isEqualTo(targetId);

        // HR_MANAGER given the account write permission still lacks the role ADMIN, also for an unknown id.
        UUID hrId = jdbc.queryForObject("SELECT id FROM user_accounts WHERE email = 'hr@example.test'", UUID.class);
        jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('HR_MANAGER', 'USER_ADMIN_WRITE_ALL')");
        Jwt hr = jwt(hrId, sessionOf(hrId), START.plus(TokenService.ACCESS_TOKEN_TTL));
        assertThatThrownBy(() -> service.items(hr, targetId)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.items(hr, UUID.randomUUID())).isInstanceOf(AccessDeniedException.class);

        // An administrator without either permission, read again on every call.
        for (String code : List.of("REQUISITIONS_READ_ALL", "USER_ADMIN_WRITE_ALL")) {
            jdbc.update("DELETE FROM role_permissions WHERE role_code = 'ADMIN' AND permission_code = ?", code);
            assertThatThrownBy(() -> service.items(admin, targetId)).as(code).isInstanceOf(AccessDeniedException.class);
            jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('ADMIN', ?)", code);
        }
        assertThat(service.items(admin, targetId).userId()).isEqualTo(targetId);

        // A token that has expired, then a session that was revoked.
        assertThatThrownBy(() -> service.items(jwt(adminId, adminSession, START), targetId))
                .isInstanceOf(AuthenticationFailureException.class);
        jdbc.update("UPDATE auth_sessions SET revoked_at = ? WHERE id = ?", Timestamp.from(START), adminSession);
        assertThatThrownBy(() -> service.items(admin, targetId)).isInstanceOf(BadCredentialsException.class);
    }

    private HttpResponse<String> items(UUID id, String token) throws Exception {
        return api.request("GET", "/api/v1/accounts/" + id + "/handover-items", null, token);
    }

    private UUID account(String email, String fullName, Role role) {
        return accounts.saveAndFlush(new Account(email, fullName, passwordHash, Set.of(role), START)).getId();
    }

    // A requisition of two people needed by 1 Dec 2026, written with SQL so it can be in any status.
    private UUID requisition(UUID department, RequisitionStatus status, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO recruitment_requisitions (id, position_id, department_id, headcount, reason, needed_by, status,
                    created_by, created_at, updated_at)
                VALUES (?, ?, ?, 2, 'NEW_HEADCOUNT', ?, ?, ?, ?, ?)
                """, id, positionId, department, LocalDate.of(2026, 12, 1), status.name(), adminId,
                Timestamp.from(createdAt), Timestamp.from(createdAt));
        return id;
    }

    private void recruiter(UUID requisition, UUID recruiter, String role, Instant assignedAt) {
        jdbc.update("""
                INSERT INTO requisition_recruiters (requisition_id, recruiter_id, assignment_role, assigned_by, assigned_at)
                VALUES (?, ?, ?, ?, ?)
                """, requisition, recruiter, role, adminId, Timestamp.from(assignedAt));
    }

    // The SUBMITTED event that starts a round. Its snapshot is the flow version and how many of its levels apply.
    private void submitted(UUID requisition, int revision, int round, UUID flow, int version, int levelCount,
                           String statusBefore, Instant at) {
        jdbc.update("""
                INSERT INTO requisition_workflow_events (id, requisition_id, revision, event_type, status_before,
                    status_after, round_number, flow_id, flow_version, level_count, salary_basis, actor_id, occurred_at)
                VALUES (?, ?, ?, 'SUBMITTED', ?, 'PENDING_APPROVAL', ?, ?, ?, ?, 60000000, ?, ?)
                """, UUID.randomUUID(), requisition, revision, statusBefore, round, flow, version, levelCount, adminId,
                Timestamp.from(at));
    }

    // A decision of one level of a round.
    private void decided(UUID requisition, int revision, int round, int level, String type, String statusAfter,
                         UUID actor, Instant at) {
        jdbc.update("""
                INSERT INTO requisition_workflow_events (id, requisition_id, revision, event_type, status_before,
                    status_after, round_number, level_number, actor_id, occurred_at, comment)
                VALUES (?, ?, ?, ?, 'PENDING_APPROVAL', ?, ?, ?, ?, ?, 'Đã xem xét')
                """, UUID.randomUUID(), requisition, revision, type, statusAfter, round, level, actor,
                Timestamp.from(at));
    }

    private UUID sessionOf(UUID userId) {
        return jdbc.queryForObject("SELECT id FROM auth_sessions WHERE user_id = ?", UUID.class, userId);
    }

    private static Jwt jwt(UUID subject, UUID session, Instant expiresAt) {
        return Jwt.withTokenValue("service-test").header("alg", "HS256")
                .subject(subject.toString()).claim("jti", session.toString())
                .issuedAt(START.minusSeconds(1)).expiresAt(expiresAt).build();
    }

    private static List<UUID> ids(JsonNode list, String field) {
        List<UUID> ids = new ArrayList<>();
        list.forEach(item -> ids.add(UUID.fromString(item.path(field).asText())));
        return ids;
    }
}
