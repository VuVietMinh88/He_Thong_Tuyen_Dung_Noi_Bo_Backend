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
import vn.ttcs.recruitment.account.handover.AccountHandoverRequest;
import vn.ttcs.recruitment.account.handover.AccountHandoverService;
import vn.ttcs.recruitment.requisition.RequisitionStatus;

import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Feedback item 10 (locking an account): POST /accounts/{id}/handover hands the requisitions of an account over to
// other recruiters, all or nothing, with the rules and the history of the recruiter assignment (tasks 284, 286).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AccountHandoverIntegrationTest {
    // 10:00 on 16 Oct 2026 in Vietnam.
    private static final Instant START = Instant.parse("2026-10-16T03:00:00Z");
    private static final String NOTE = "Nghỉ việc từ 20/10";
    private static final String NOT_HELD = "Công việc này không còn thuộc tài khoản cần bàn giao. Vui lòng tải lại danh sách.";
    private static final String NOT_RECRUITER = "Người được phân công phải là tài khoản tồn tại và có vai trò Recruiter.";
    private static final String INACTIVE = "Tài khoản này đang bị khóa hoặc chưa kích hoạt nên không thể được phân công.";
    private static final String INVALID_MANAGER =
            "Người quản lý phải là tài khoản tồn tại và được phép truy cập hệ thống khi được bổ nhiệm.";

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
    private UUID hrId;
    private String hrToken;
    private UUID targetId;
    private UUID otherId;
    private UUID recipientId;
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
        hrId = account("hr@example.test", "Nguyễn Thị Hoa", Role.HR_MANAGER);
        hrToken = api.token("hr@example.test");
        targetId = account("target@example.test", "Nguyễn Văn A", Role.RECRUITER);
        otherId = account("other@example.test", "Trần Thị B", Role.RECRUITER);
        recipientId = account("recipient@example.test", "Lê Văn C", Role.RECRUITER);
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
    void thePrimaryRecruiterHandsTheRequisitionOverWithTheNote() throws Exception {
        UUID handed = requisition(RequisitionStatus.APPROVED);
        recruiter(handed, targetId, "PRIMARY");
        recruiter(handed, otherId, "SUPPORTING");
        // Not in the request, so still held afterwards.
        UUID kept = requisition(RequisitionStatus.DRAFT);
        recruiter(kept, otherId, "PRIMARY");
        recruiter(kept, targetId, "SUPPORTING");

        var response = handOver(targetId, body(NOTE, item(handed, recipientId)), adminToken);
        JsonNode result = api.expect(response, 200);

        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(result.size()).isEqualTo(2);
        assertThat(result.path("transferred").asInt()).isEqualTo(1);
        JsonNode remaining = result.path("remaining");
        assertThat(remaining.path("userId").asText()).isEqualTo(targetId.toString());
        // The handover never locks the account.
        assertThat(remaining.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(ids(remaining.path("requisitions"), "requisitionId")).containsExactly(kept);
        for (String list : List.of("managedDepartments", "approvalSteps", "pendingApprovals")) {
            assertThat(remaining.path(list).isArray()).as(list).isTrue();
        }

        assertThat(team(handed)).containsExactly(recipientId + ":PRIMARY", otherId + ":SUPPORTING");
        assertThat(team(kept)).containsExactly(otherId + ":PRIMARY", targetId + ":SUPPORTING");
        // The same history as POST /requisitions/{id}/assign, with the note of the request and the caller.
        JsonNode history = api.expect(api.request("GET", "/api/v1/requisitions/" + handed + "/assignment-history",
                null, adminToken), 200);
        assertThat(history.size()).isOne();
        assertThat(history.path(0).path("changeType").asText()).isEqualTo("PRIMARY_HANDED_OVER");
        assertThat(history.path(0).path("recruiterId").asText()).isEqualTo(recipientId.toString());
        assertThat(history.path(0).path("previousRecruiterId").asText()).isEqualTo(targetId.toString());
        assertThat(history.path(0).path("assignedById").asText()).isEqualTo(adminId.toString());
        assertThat(Instant.parse(history.path(0).path("assignedAt").asText())).isEqualTo(START);
        assertThat(history.path(0).path("note").asText()).isEqualTo(NOTE);
        assertThat(history(kept)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT admin_locked_at FROM user_accounts WHERE id = ?", Timestamp.class,
                targetId)).isNull();
    }

    @Test
    void aSupportingRecruiterLeavesTheTeamAndTheRecipientJoinsIt() throws Exception {
        UUID requisition = requisition(RequisitionStatus.PAUSED);
        recruiter(requisition, otherId, "PRIMARY");
        recruiter(requisition, targetId, "SUPPORTING");

        // A blank note is stored as no note, like an assignment.
        JsonNode result = api.expect(handOver(targetId, body("   ", item(requisition, recipientId)), adminToken), 200);

        assertThat(result.path("transferred").asInt()).isEqualTo(1);
        assertThat(result.path("remaining").path("requisitions").isEmpty()).isTrue();
        assertThat(team(requisition)).containsExactly(otherId + ":PRIMARY", recipientId + ":SUPPORTING");
        assertThat(history(requisition)).containsExactly("SUPPORTING_REMOVED:" + targetId + ":-:-",
                "SUPPORTING_ADDED:" + recipientId + ":-:-");
    }

    @Test
    void aRecipientAlreadyOnTheTeamKeepsItsRoleOrIsPromoted() throws Exception {
        // Both supporting: the account only leaves.
        UUID bothSupporting = requisition(RequisitionStatus.APPROVED);
        recruiter(bothSupporting, otherId, "PRIMARY");
        recruiter(bothSupporting, targetId, "SUPPORTING");
        recruiter(bothSupporting, recipientId, "SUPPORTING");
        // The recipient is the primary already: the account only leaves.
        UUID recipientPrimary = requisition(RequisitionStatus.RETURNED);
        recruiter(recipientPrimary, recipientId, "PRIMARY");
        recruiter(recipientPrimary, targetId, "SUPPORTING");
        // The account is the primary and the recipient supporting: the recipient is promoted.
        UUID promoted = requisition(RequisitionStatus.PENDING_APPROVAL);
        recruiter(promoted, targetId, "PRIMARY");
        recruiter(promoted, recipientId, "SUPPORTING");

        JsonNode result = api.expect(handOver(targetId, body(NOTE, item(bothSupporting, recipientId),
                item(recipientPrimary, recipientId), item(promoted, recipientId)), adminToken), 200);

        assertThat(result.path("transferred").asInt()).isEqualTo(3);
        assertThat(result.path("remaining").path("requisitions").isEmpty()).isTrue();
        assertThat(team(bothSupporting)).containsExactly(otherId + ":PRIMARY", recipientId + ":SUPPORTING");
        assertThat(history(bothSupporting)).containsExactly("SUPPORTING_REMOVED:" + targetId + ":-:" + NOTE);
        assertThat(team(recipientPrimary)).containsExactly(recipientId + ":PRIMARY");
        assertThat(history(recipientPrimary)).containsExactly("SUPPORTING_REMOVED:" + targetId + ":-:" + NOTE);
        assertThat(team(promoted)).containsExactly(recipientId + ":PRIMARY");
        assertThat(history(promoted)).containsExactly("PRIMARY_HANDED_OVER:" + recipientId + ":" + targetId + ":" + NOTE);
    }

    @Test
    void aFullTeamTakesTheRecipientBecauseTheAccountLeavesFirst() throws Exception {
        UUID requisition = requisition(RequisitionStatus.APPROVED);
        recruiter(requisition, otherId, "PRIMARY");
        recruiter(requisition, targetId, "SUPPORTING");
        for (int index = 0; index < 9; index++) {
            recruiter(requisition, account("s" + index + "@example.test", "Hỗ trợ " + index, Role.RECRUITER),
                    "SUPPORTING");
        }

        api.expect(handOver(targetId, body(null, item(requisition, recipientId)), adminToken), 200);

        List<String> team = team(requisition);
        assertThat(team).hasSize(11).contains(recipientId + ":SUPPORTING").doesNotContain(targetId + ":SUPPORTING");
    }

    @Test
    void onlyAnActiveRecruiterReceivesWorkAndTheErrorNamesTheItem() throws Exception {
        UUID first = requisition(RequisitionStatus.APPROVED);
        recruiter(first, targetId, "PRIMARY");
        UUID second = requisition(RequisitionStatus.DRAFT);
        recruiter(second, otherId, "PRIMARY");
        recruiter(second, targetId, "SUPPORTING");
        UUID interviewer = account("interviewer@example.test", "Người phỏng vấn", Role.INTERVIEWER);
        UUID adminLocked = account("locked@example.test", "Bị khóa", Role.RECRUITER);
        jdbc.update("UPDATE user_accounts SET admin_locked_at = ?, admin_lock_reason = 'Review', admin_locked_by = ? WHERE id = ?",
                Timestamp.from(START), adminId, adminLocked);
        UUID pending = account("pending@example.test", "Chưa kích hoạt", Role.RECRUITER);
        jdbc.update("UPDATE user_accounts SET enabled = FALSE WHERE id = ?", pending);

        // The first item is valid; the second names the wrong person, as primary or as supporting recruiter.
        Map<UUID, String> wrong = new LinkedHashMap<>();
        wrong.put(UUID.randomUUID(), "INVALID_REQUISITION_RECRUITER");
        wrong.put(hrId, "INVALID_REQUISITION_RECRUITER");
        wrong.put(interviewer, "INVALID_REQUISITION_RECRUITER");
        wrong.put(adminLocked, "REQUISITION_RECRUITER_INACTIVE");
        wrong.put(pending, "REQUISITION_RECRUITER_INACTIVE");
        for (var person : wrong.entrySet()) {
            for (var items : List.of(List.of(item(second, recipientId), item(first, person.getKey())),
                    List.of(item(first, recipientId), item(second, person.getKey())))) {
                JsonNode error = api.expect(handOver(targetId, body(NOTE, items), adminToken), 400);
                assertThat(error.path("code").asText()).as(person.getValue()).isEqualTo(person.getValue());
                String message = person.getValue().equals("REQUISITION_RECRUITER_INACTIVE") ? INACTIVE : NOT_RECRUITER;
                assertThat(error.path("message").asText()).isEqualTo(message);
                assertThat(error.path("fieldErrors").size()).isOne();
                assertThat(error.path("fieldErrors").path("requisitions[1].toUserId").asText()).isEqualTo(message);
            }
        }
        // All or nothing: the valid first item was undone each time.
        assertThat(team(first)).containsExactly(targetId + ":PRIMARY");
        assertThat(team(second)).containsExactly(otherId + ":PRIMARY", targetId + ":SUPPORTING");
        assertThat(history(first)).isEmpty();
        assertThat(history(second)).isEmpty();

        // A temporary lockout after wrong passwords ends by itself. A recipient already on the team is not checked
        // again, even when locked by an administrator.
        UUID tempLocked = account("temp@example.test", "Tạm khóa", Role.RECRUITER);
        jdbc.update("UPDATE user_accounts SET locked_until = ? WHERE id = ?",
                Timestamp.from(START.plus(Duration.ofMinutes(10))), tempLocked);
        recruiter(second, adminLocked, "SUPPORTING");
        api.expect(handOver(targetId, body(NOTE, item(first, tempLocked), item(second, adminLocked)), adminToken), 200);
        assertThat(team(first)).containsExactly(tempLocked + ":PRIMARY");
        assertThat(team(second)).containsExactlyInAnyOrder(otherId + ":PRIMARY", adminLocked + ":SUPPORTING");
    }

    // Only a recipient who keeps its role is not checked again: promoting a supporting member to primary gives it a
    // new role, so a member locked by an administrator is refused like a newcomer.
    @Test
    void aLockedSupportingMemberIsNotPromotedToPrimary() throws Exception {
        UUID requisition = requisition(RequisitionStatus.APPROVED);
        recruiter(requisition, targetId, "PRIMARY");
        UUID adminLocked = account("locked@example.test", "Bị khóa", Role.RECRUITER);
        recruiter(requisition, adminLocked, "SUPPORTING");
        jdbc.update("UPDATE user_accounts SET admin_locked_at = ?, admin_lock_reason = 'Review', admin_locked_by = ? WHERE id = ?",
                Timestamp.from(START), adminId, adminLocked);

        JsonNode error = api.expect(handOver(targetId, body(NOTE, item(requisition, adminLocked)), adminToken), 400);

        assertThat(error.path("code").asText()).isEqualTo("REQUISITION_RECRUITER_INACTIVE");
        assertThat(error.path("fieldErrors").size()).isOne();
        assertThat(error.path("fieldErrors").path("requisitions[0].toUserId").asText()).isEqualTo(INACTIVE);
        assertThat(team(requisition)).containsExactly(targetId + ":PRIMARY", adminLocked + ":SUPPORTING");
        assertThat(history(requisition)).isEmpty();
    }

    @Test
    void aRequisitionThatIsFinishedOrNoLongerHeldIsAConflict() throws Exception {
        for (RequisitionStatus status : List.of(RequisitionStatus.REJECTED, RequisitionStatus.FILLED,
                RequisitionStatus.CANCELLED)) {
            UUID finished = requisition(status);
            recruiter(finished, targetId, "PRIMARY");
            JsonNode error = api.expect(handOver(targetId, body(NOTE, item(finished, recipientId)), adminToken), 409);
            assertThat(error.path("code").asText()).as(status.name()).isEqualTo("REQUISITION_NOT_ASSIGNABLE");
            String message = "Không thể phân công recruiter cho yêu cầu tuyển dụng ở trạng thái hiện tại.";
            assertThat(error.path("message").asText()).isEqualTo(message);
            assertThat(error.path("fieldErrors").path("requisitions[0].requisitionId").asText()).isEqualTo(message);
            assertThat(team(finished)).containsExactly(targetId + ":PRIMARY");
        }
        // Handed over by someone else meanwhile, never assigned, or no such requisition.
        UUID foreign = requisition(RequisitionStatus.APPROVED);
        recruiter(foreign, otherId, "PRIMARY");
        UUID unassigned = requisition(RequisitionStatus.APPROVED);
        for (UUID requisition : List.of(foreign, unassigned, UUID.randomUUID())) {
            var response = handOver(targetId, body(NOTE, item(requisition, recipientId)), adminToken);
            JsonNode error = api.expect(response, 409);
            assertThat(error.path("code").asText()).isEqualTo("HANDOVER_ITEM_NOT_HELD");
            assertThat(error.path("message").asText()).isEqualTo(NOT_HELD);
            assertThat(error.path("fieldErrors").path("requisitions[0].requisitionId").asText()).isEqualTo(NOT_HELD);
            assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        }
        assertThat(team(foreign)).containsExactly(otherId + ":PRIMARY");
        assertThat(team(unassigned)).isEmpty();
    }

    @Test
    void aFailingItemUndoesTheItemsHandedOverBeforeIt() throws Exception {
        UUID first = requisition(RequisitionStatus.APPROVED);
        recruiter(first, targetId, "PRIMARY");
        UUID second = requisition(RequisitionStatus.APPROVED);
        recruiter(second, otherId, "PRIMARY");
        recruiter(second, targetId, "SUPPORTING");
        UUID lost = requisition(RequisitionStatus.APPROVED);
        recruiter(lost, otherId, "PRIMARY");

        JsonNode error = api.expect(handOver(targetId, body(NOTE, item(first, recipientId), item(second, recipientId),
                item(lost, recipientId)), adminToken), 409);

        assertThat(error.path("code").asText()).isEqualTo("HANDOVER_ITEM_NOT_HELD");
        assertThat(error.path("fieldErrors").has("requisitions[2].requisitionId")).isTrue();
        assertThat(team(first)).containsExactly(targetId + ":PRIMARY");
        assertThat(team(second)).containsExactly(otherId + ":PRIMARY", targetId + ":SUPPORTING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM requisition_recruiter_changes", Integer.class)).isZero();
    }

    @Test
    void theBodyIsCheckedBeforeAnythingIsChanged() throws Exception {
        UUID requisition = requisition(RequisitionStatus.APPROVED);
        recruiter(requisition, targetId, "PRIMARY");
        UUID another = requisition(RequisitionStatus.APPROVED);
        recruiter(another, targetId, "PRIMARY");
        String choose = "Vui lòng chọn ít nhất một công việc để bàn giao.";

        assertFieldError(handOver(targetId, "{}", adminToken), "requisitions", choose);
        assertFieldError(handOver(targetId, json.writeValueAsString(Map.of("note", NOTE, "requisitions", List.of())),
                adminToken), "requisitions", choose);
        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int index = 0; index < 101; index++) {
            tooMany.add(item(UUID.randomUUID(), recipientId));
        }
        assertFieldError(handOver(targetId, json.writeValueAsString(Map.of("requisitions", tooMany)), adminToken),
                "requisitions", "Mỗi lần bàn giao tối đa 100 yêu cầu tuyển dụng.");
        List<Object> withNull = new ArrayList<>();
        withNull.add(null);
        Map<String, Object> nullItem = new HashMap<>();
        nullItem.put("requisitions", withNull);
        assertFieldError(handOver(targetId, json.writeValueAsString(nullItem), adminToken), "requisitions[0]",
                "Vui lòng chọn yêu cầu tuyển dụng và người nhận bàn giao.");
        assertFieldError(handOver(targetId, json.writeValueAsString(Map.of("requisitions",
                List.of(Map.of("requisitionId", requisition)))), adminToken), "requisitions[0].toUserId",
                "Vui lòng chọn người nhận bàn giao.");
        assertFieldError(handOver(targetId, json.writeValueAsString(Map.of("requisitions",
                List.of(Map.of("toUserId", recipientId)))), adminToken), "requisitions[0].requisitionId",
                "Vui lòng chọn yêu cầu tuyển dụng cần bàn giao.");
        // The second time a requisition is named, and the account itself as the recipient.
        assertFieldError(handOver(targetId, body(NOTE, item(requisition, recipientId), item(another, recipientId),
                item(requisition, otherId)), adminToken), "requisitions[2].requisitionId",
                "Mỗi yêu cầu tuyển dụng chỉ được bàn giao một lần.");
        assertFieldError(handOver(targetId, body(NOTE, item(requisition, recipientId), item(another, targetId)),
                adminToken), "requisitions[1].toUserId", "Người nhận phải khác tài khoản đang bàn giao.");
        assertFieldError(handOver(targetId, body("x".repeat(1_001), item(requisition, recipientId)), adminToken),
                "note", "Ghi chú tối đa 1.000 ký tự.");
        assertFieldError(handOver(targetId, body("Ghi chú" + (char) 0, item(requisition, recipientId)), adminToken),
                "note", "Ghi chú chứa ký tự không hợp lệ.");

        // The client never chooses a role, and ids are UUIDs.
        Map<String, Object> withRole = new HashMap<>(item(requisition, recipientId));
        withRole.put("role", "PRIMARY");
        assertInvalidJson(handOver(targetId, json.writeValueAsString(Map.of("requisitions", List.of(withRole))),
                adminToken));
        assertInvalidJson(handOver(targetId, json.writeValueAsString(Map.of("requisitions",
                List.of(Map.of("requisitionId", requisition, "toUserId", "HR01")))), adminToken));
        assertInvalidJson(handOver(targetId, (String) null, adminToken));
        assertThat(api.code(api.request("POST", "/api/v1/accounts/HR01/handover",
                json.writeValueAsString(body(NOTE, item(requisition, recipientId))), adminToken), 400))
                .isEqualTo("VALIDATION_ERROR");

        assertThat(team(requisition)).containsExactly(targetId + ":PRIMARY");
        assertThat(team(another)).containsExactly(targetId + ":PRIMARY");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM requisition_recruiter_changes", Integer.class)).isZero();
    }

    @Test
    void onlyAnAdministratorWhoMayAssignRecruitersHandsWorkOver() throws Exception {
        UUID requisition = requisition(RequisitionStatus.APPROVED);
        recruiter(requisition, targetId, "PRIMARY");
        String valid = json.writeValueAsString(body(NOTE, item(requisition, recipientId)));

        assertThat(api.code(handOver(targetId, valid, null), 401)).isEqualTo("UNAUTHORIZED");
        // HR_MANAGER assigns recruiters but may not administer accounts, not even with the account write permission.
        api.forbidden(handOver(targetId, valid, hrToken));
        jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('HR_MANAGER', 'USER_ADMIN_WRITE_ALL')");
        api.forbidden(handOver(targetId, valid, hrToken));
        // The account itself cannot hand its work over.
        api.forbidden(handOver(targetId, valid, api.token("target@example.test")));
        // An administrator without any one of the three permissions is refused on the next request.
        for (String code : List.of("REQUISITIONS_WRITE_ALL", "REQUISITIONS_READ_ALL", "USER_ADMIN_WRITE_ALL")) {
            jdbc.update("DELETE FROM role_permissions WHERE role_code = 'ADMIN' AND permission_code = ?", code);
            api.forbidden(handOver(targetId, valid, adminToken));
            jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('ADMIN', ?)", code);
        }
        assertThat(team(requisition)).containsExactly(targetId + ":PRIMARY");

        // An unknown account is 404, after the caller was checked.
        JsonNode error = api.expect(handOver(UUID.randomUUID(), valid, adminToken), 404);
        assertThat(error.path("code").asText()).isEqualTo("ACCOUNT_NOT_FOUND");

        api.expect(handOver(targetId, valid, adminToken), 200);
        assertThat(team(requisition)).containsExactly(recipientId + ":PRIMARY");
    }

    // The matcher refuses each of these first; the service checks the token, the session, the role and the
    // permissions (read again) on its own as well, after its locks.
    @Test
    void serviceChecksTheTokenSessionRoleAndPermissionsAgainWhenCalledDirectly() {
        UUID requisition = requisition(RequisitionStatus.APPROVED);
        recruiter(requisition, targetId, "PRIMARY");
        AccountHandoverRequest request = new AccountHandoverRequest(NOTE,
                List.of(new AccountHandoverRequest.Item(requisition, recipientId)), null);
        UUID adminSession = sessionOf(adminId);
        Jwt admin = jwt(adminId, adminSession, START.plus(TokenService.ACCESS_TOKEN_TTL));

        // HR_MANAGER given the account write permission still lacks the role ADMIN, also for an unknown id.
        jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('HR_MANAGER', 'USER_ADMIN_WRITE_ALL')");
        Jwt hr = jwt(hrId, sessionOf(hrId), START.plus(TokenService.ACCESS_TOKEN_TTL));
        assertThatThrownBy(() -> service.handOver(hr, targetId, request)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.handOver(hr, UUID.randomUUID(), request))
                .isInstanceOf(AccessDeniedException.class);
        for (String code : List.of("REQUISITIONS_WRITE_ALL", "REQUISITIONS_READ_ALL", "USER_ADMIN_WRITE_ALL")) {
            jdbc.update("DELETE FROM role_permissions WHERE role_code = 'ADMIN' AND permission_code = ?", code);
            assertThatThrownBy(() -> service.handOver(admin, targetId, request)).as(code)
                    .isInstanceOf(AccessDeniedException.class);
            jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('ADMIN', ?)", code);
        }
        // A token that has expired, a session that was revoked, then a caller locked by another administrator.
        assertThatThrownBy(() -> service.handOver(jwt(adminId, adminSession, START), targetId, request))
                .isInstanceOf(AuthenticationFailureException.class);
        jdbc.update("UPDATE auth_sessions SET revoked_at = ? WHERE id = ?", Timestamp.from(START), adminSession);
        assertThatThrownBy(() -> service.handOver(admin, targetId, request))
                .isInstanceOf(AuthenticationFailureException.class);
        UUID hrSession = sessionOf(hrId);
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'ADMIN')", hrId);
        Jwt hrAdmin = jwt(hrId, hrSession, START.plus(TokenService.ACCESS_TOKEN_TTL));
        jdbc.update("UPDATE user_accounts SET admin_locked_at = ?, admin_lock_reason = 'Review', admin_locked_by = ? WHERE id = ?",
                Timestamp.from(START), adminId, hrId);
        assertThatThrownBy(() -> service.handOver(hrAdmin, targetId, request))
                .isInstanceOf(AuthenticationFailureException.class);
        assertThat(team(requisition)).containsExactly(targetId + ":PRIMARY");

        jdbc.update("UPDATE user_accounts SET admin_locked_at = NULL, admin_lock_reason = NULL, admin_locked_by = NULL WHERE id = ?",
                hrId);
        assertThat(service.handOver(hrAdmin, targetId, request).transferred()).isOne();
        assertThat(team(requisition)).containsExactly(recipientId + ":PRIMARY");
    }

    // Task 285: who may read a requisition follows the current team, so the handover moves the access with the work.
    @Test
    void theRecipientGainsAccessToTheRequisitionAndTheAccountLosesIt() throws Exception {
        UUID requisition = requisition(RequisitionStatus.APPROVED);
        recruiter(requisition, targetId, "PRIMARY");
        String targetToken = api.token("target@example.test");
        String recipientToken = api.token("recipient@example.test");
        String path = "/api/v1/requisitions/" + requisition;
        api.expect(api.request("GET", path, null, targetToken), 200);
        api.forbidden(api.request("GET", path, null, recipientToken));

        api.expect(handOver(targetId, body(NOTE, item(requisition, recipientId)), adminToken), 200);

        api.forbidden(api.request("GET", path, null, targetToken));
        api.expect(api.request("GET", path, null, recipientToken), 200);
    }

    @Test
    void anAccountAlreadyLockedStillHandsItsWorkOver() throws Exception {
        UUID requisition = requisition(RequisitionStatus.APPROVED);
        recruiter(requisition, targetId, "PRIMARY");
        api.expect(api.request("PUT", "/api/v1/accounts/" + targetId + "/lock",
                json.writeValueAsString(Map.of("reason", "Nghỉ việc")), adminToken), 200);

        JsonNode result = api.expect(handOver(targetId, body(NOTE, item(requisition, recipientId)), adminToken), 200);

        assertThat(result.path("remaining").path("status").asText()).isEqualTo("ADMINISTRATIVELY_LOCKED");
        assertThat(result.path("remaining").path("requisitions").isEmpty()).isTrue();
        assertThat(team(requisition)).containsExactly(recipientId + ":PRIMARY");
        assertThat(history(requisition)).containsExactly(
                "PRIMARY_HANDED_OVER:" + recipientId + ":" + targetId + ":" + NOTE);
    }

    // Feedback item 10, part 3: the departments the account manages directly get other managers, in the same request
    // and the same transaction as its requisitions. Only the manager changes; manager changes have no history.
    @Test
    void theDepartmentsOfTheAccountGetTheRecipientsAsManagers() throws Exception {
        UUID newHead = account("newhead@example.test", "Trưởng phòng mới", Role.HIRING_MANAGER);
        UUID ops = api.department("OPS", targetId, null, START);
        // Inactive, but with an open requisition of its own: listed, so it is handed over too.
        UUID sales = api.department("SALES", targetId, itId, START);
        jdbc.update("UPDATE departments SET active = FALSE WHERE id = ?", sales);
        requisition(RequisitionStatus.DRAFT, sales);
        // Not in the request, so still held afterwards.
        UUID kept = api.department("QA", targetId, null, START);
        UUID requisition = requisition(RequisitionStatus.APPROVED);
        recruiter(requisition, targetId, "PRIMARY");
        Map<String, Object> body = body(NOTE, item(requisition, recipientId));
        body.put("departments", List.of(departmentItem(ops, newHead), departmentItem(sales, recipientId)));

        var response = handOver(targetId, body, adminToken);
        JsonNode result = api.expect(response, 200);

        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(result.path("transferred").asInt()).isEqualTo(3);
        assertThat(result.path("remaining").path("requisitions").isEmpty()).isTrue();
        assertThat(ids(result.path("remaining").path("managedDepartments"), "departmentId")).containsExactly(kept);
        assertThat(manager(ops)).isEqualTo(newHead);
        assertThat(manager(sales)).isEqualTo(recipientId);
        assertThat(manager(kept)).isEqualTo(targetId);
        assertThat(jdbc.queryForObject("""
                SELECT code || ':' || name || ':' || parent_id || ':' || active FROM departments WHERE id = ?
                """, String.class, sales)).isEqualTo("SALES:Phòng SALES:" + itId + ":false");
        assertThat(team(requisition)).containsExactly(recipientId + ":PRIMARY");
        assertThat(history(requisition)).containsExactly(
                "PRIMARY_HANDED_OVER:" + recipientId + ":" + targetId + ":" + NOTE);
        JsonNode department = api.expect(api.request("GET", "/api/v1/departments/" + ops, null, adminToken), 200);
        assertThat(department.path("managerUserId").asText()).isEqualTo(newHead.toString());
        assertThat(department.path("managerFullName").asText()).isEqualTo("Trưởng phòng mới");
    }

    // Who manages a department decides who reads its requisitions, and those of every department below it, with a
    // SCOPED permission (a HIRING_MANAGER): the handover moves that scope as soon as it commits.
    @Test
    void theNewManagerTakesOverTheScopeOfTheDepartmentAndOfTheDepartmentsBelowIt() throws Exception {
        UUID newHead = account("newhead@example.test", "Trưởng phòng mới", Role.HIRING_MANAGER);
        UUID devHead = account("devhead@example.test", "Trưởng nhóm Dev", Role.HIRING_MANAGER);
        UUID dev = api.department("IT_DEV", devHead, itId, START);
        UUID inIt = requisition(RequisitionStatus.APPROVED);
        UUID inDev = requisition(RequisitionStatus.DRAFT, dev);
        String headToken = api.token("head@example.test");
        String newHeadToken = api.token("newhead@example.test");
        for (UUID requisition : List.of(inIt, inDev)) {
            api.expect(api.request("GET", "/api/v1/requisitions/" + requisition, null, headToken), 200);
            api.forbidden(api.request("GET", "/api/v1/requisitions/" + requisition, null, newHeadToken));
        }

        JsonNode result = api.expect(handOver(headId, departmentBody(null, departmentItem(itId, newHead)), adminToken),
                200);

        assertThat(result.path("transferred").asInt()).isOne();
        assertThat(result.path("remaining").path("managedDepartments").isEmpty()).isTrue();
        for (UUID requisition : List.of(inIt, inDev)) {
            api.forbidden(api.request("GET", "/api/v1/requisitions/" + requisition, null, headToken));
            api.expect(api.request("GET", "/api/v1/requisitions/" + requisition, null, newHeadToken), 200);
        }
        // The department below keeps its own manager, who still reads its requisitions.
        assertThat(manager(dev)).isEqualTo(devHead);
        api.expect(api.request("GET", "/api/v1/requisitions/" + inDev, null, api.token("devhead@example.test")), 200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM requisition_recruiter_changes", Integer.class)).isZero();
    }

    @Test
    void aDepartmentTheAccountNoLongerManagesIsAConflict() throws Exception {
        UUID newHead = account("newhead@example.test", "Trưởng phòng mới", Role.HIRING_MANAGER);
        // Inactive without open requisitions: not listed, nothing to hand over.
        UUID closed = api.department("OLD", targetId, null, START);
        jdbc.update("UPDATE departments SET active = FALSE WHERE id = ?", closed);
        requisition(RequisitionStatus.FILLED, closed);
        JsonNode items = api.expect(api.request("GET", "/api/v1/accounts/" + targetId + "/handover-items", null,
                adminToken), 200);
        assertThat(items.path("managedDepartments").isEmpty()).isTrue();

        // Managed by someone else (handed over meanwhile), not listed, or no such department.
        for (UUID department : List.of(itId, closed, UUID.randomUUID())) {
            var response = handOver(targetId, departmentBody(NOTE, departmentItem(department, newHead)), adminToken);
            JsonNode error = api.expect(response, 409);
            assertThat(error.path("code").asText()).isEqualTo("HANDOVER_ITEM_NOT_HELD");
            assertThat(error.path("message").asText()).isEqualTo(NOT_HELD);
            assertThat(error.path("fieldErrors").size()).isOne();
            assertThat(error.path("fieldErrors").path("departments[0].departmentId").asText()).isEqualTo(NOT_HELD);
            assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        }
        assertThat(manager(itId)).isEqualTo(headId);
        assertThat(manager(closed)).isEqualTo(targetId);
    }

    @Test
    void onlyAnAccountThatMaySignInBecomesTheManagerAndTheErrorNamesTheItem() throws Exception {
        UUID ops = api.department("OPS", targetId, null, START);
        UUID sales = api.department("SALES", targetId, null, START);
        UUID requisition = requisition(RequisitionStatus.APPROVED);
        recruiter(requisition, targetId, "PRIMARY");
        UUID adminLocked = account("locked@example.test", "Bị khóa", Role.HIRING_MANAGER);
        jdbc.update("UPDATE user_accounts SET admin_locked_at = ?, admin_lock_reason = 'Review', admin_locked_by = ? WHERE id = ?",
                Timestamp.from(START), adminId, adminLocked);
        UUID pending = account("pending@example.test", "Chưa kích hoạt", Role.HIRING_MANAGER);
        jdbc.update("UPDATE user_accounts SET enabled = FALSE WHERE id = ?", pending);

        // The requisition and the first department are valid; the second department names the wrong person.
        for (UUID person : List.of(UUID.randomUUID(), adminLocked, pending)) {
            Map<String, Object> body = body(NOTE, item(requisition, recipientId));
            body.put("departments", List.of(departmentItem(ops, otherId), departmentItem(sales, person)));
            JsonNode error = api.expect(handOver(targetId, body, adminToken), 400);
            assertThat(error.path("code").asText()).isEqualTo("INVALID_DEPARTMENT_MANAGER");
            assertThat(error.path("message").asText()).isEqualTo(INVALID_MANAGER);
            assertThat(error.path("fieldErrors").size()).isOne();
            assertThat(error.path("fieldErrors").path("departments[1].toUserId").asText()).isEqualTo(INVALID_MANAGER);
        }
        // All or nothing: the requisition and the first department were undone each time.
        assertThat(team(requisition)).containsExactly(targetId + ":PRIMARY");
        assertThat(history(requisition)).isEmpty();
        assertThat(manager(ops)).isEqualTo(targetId);
        assertThat(manager(sales)).isEqualTo(targetId);

        // Any role, as in the department API; a temporary lockout after wrong passwords ends by itself.
        UUID interviewer = account("interviewer@example.test", "Người phỏng vấn", Role.INTERVIEWER);
        UUID tempLocked = account("temp@example.test", "Tạm khóa", Role.RECRUITER);
        jdbc.update("UPDATE user_accounts SET locked_until = ? WHERE id = ?",
                Timestamp.from(START.plus(Duration.ofMinutes(10))), tempLocked);
        api.expect(handOver(targetId, departmentBody(NOTE, departmentItem(ops, interviewer),
                departmentItem(sales, tempLocked)), adminToken), 200);
        assertThat(manager(ops)).isEqualTo(interviewer);
        assertThat(manager(sales)).isEqualTo(tempLocked);
    }

    // The matcher of POST /handover cannot see the body; the service asks for ORGANIZATION_WRITE_ALL, the permission
    // of PUT /departments/{id}, only when the request moves departments.
    @Test
    void handingDepartmentsOverAlsoNeedsTheOrganizationWritePermission() throws Exception {
        UUID ops = api.department("OPS", targetId, null, START);
        UUID requisition = requisition(RequisitionStatus.APPROVED);
        recruiter(requisition, targetId, "PRIMARY");
        Map<String, Object> both = body(NOTE, item(requisition, recipientId));
        both.put("departments", List.of(departmentItem(ops, otherId)));
        jdbc.update("DELETE FROM role_permissions WHERE role_code = 'ADMIN' AND permission_code = 'ORGANIZATION_WRITE_ALL'");

        api.forbidden(handOver(targetId, both, adminToken));
        api.forbidden(handOver(targetId, departmentBody(NOTE, departmentItem(ops, otherId)), adminToken));
        assertThat(team(requisition)).containsExactly(targetId + ":PRIMARY");
        assertThat(manager(ops)).isEqualTo(targetId);
        // Without departments it is not needed.
        api.expect(handOver(targetId, body(NOTE, item(requisition, recipientId)), adminToken), 200);
        assertThat(team(requisition)).containsExactly(recipientId + ":PRIMARY");

        // HR_MANAGER has ORGANIZATION_WRITE_ALL and REQUISITIONS_WRITE_ALL, but not the role ADMIN.
        jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('ADMIN', 'ORGANIZATION_WRITE_ALL')");
        api.forbidden(handOver(targetId, departmentBody(NOTE, departmentItem(ops, otherId)), hrToken));
        assertThat(manager(ops)).isEqualTo(targetId);
        api.expect(handOver(targetId, departmentBody(NOTE, departmentItem(ops, otherId)), adminToken), 200);
        assertThat(manager(ops)).isEqualTo(otherId);
    }

    @Test
    void theDepartmentsOfTheBodyAreCheckedBeforeAnythingIsChanged() throws Exception {
        UUID ops = api.department("OPS", targetId, null, START);
        UUID sales = api.department("SALES", targetId, null, START);

        assertFieldError(handOver(targetId, json.writeValueAsString(Map.of("requisitions", List.of(),
                "departments", List.of())), adminToken), "requisitions",
                "Vui lòng chọn ít nhất một công việc để bàn giao.");
        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int index = 0; index < 101; index++) {
            tooMany.add(departmentItem(UUID.randomUUID(), recipientId));
        }
        assertFieldError(handOver(targetId, json.writeValueAsString(Map.of("departments", tooMany)), adminToken),
                "departments", "Mỗi lần bàn giao tối đa 100 phòng ban.");
        List<Object> withNull = new ArrayList<>();
        withNull.add(null);
        Map<String, Object> nullItem = new HashMap<>();
        nullItem.put("departments", withNull);
        assertFieldError(handOver(targetId, json.writeValueAsString(nullItem), adminToken), "departments[0]",
                "Vui lòng chọn phòng ban và người nhận bàn giao.");
        assertFieldError(handOver(targetId, json.writeValueAsString(Map.of("departments",
                List.of(Map.of("departmentId", ops)))), adminToken), "departments[0].toUserId",
                "Vui lòng chọn người nhận bàn giao.");
        assertFieldError(handOver(targetId, json.writeValueAsString(Map.of("departments",
                List.of(Map.of("toUserId", recipientId)))), adminToken), "departments[0].departmentId",
                "Vui lòng chọn phòng ban cần bàn giao.");
        // The second time a department is named, and the account itself as the recipient.
        assertFieldError(handOver(targetId, departmentBody(NOTE, departmentItem(ops, otherId),
                departmentItem(sales, otherId), departmentItem(ops, recipientId)), adminToken),
                "departments[2].departmentId", "Mỗi phòng ban chỉ được bàn giao một lần.");
        assertFieldError(handOver(targetId, departmentBody(NOTE, departmentItem(ops, otherId),
                departmentItem(sales, targetId)), adminToken), "departments[1].toUserId",
                "Người nhận phải khác tài khoản đang bàn giao.");
        // The client never sends anything else about a department, and ids are UUIDs.
        Map<String, Object> withCode = new HashMap<>(departmentItem(ops, otherId));
        withCode.put("code", "OPS");
        assertInvalidJson(handOver(targetId, json.writeValueAsString(Map.of("departments", List.of(withCode))),
                adminToken));
        assertInvalidJson(handOver(targetId, json.writeValueAsString(Map.of("departments",
                List.of(Map.of("departmentId", "OPS", "toUserId", otherId)))), adminToken));

        assertThat(manager(ops)).isEqualTo(targetId);
        assertThat(manager(sales)).isEqualTo(targetId);
    }

    private HttpResponse<String> handOver(UUID id, Map<String, Object> body, String token) throws Exception {
        return handOver(id, json.writeValueAsString(body), token);
    }

    private HttpResponse<String> handOver(UUID id, String body, String token) throws Exception {
        return api.request("POST", "/api/v1/accounts/" + id + "/handover", body, token);
    }

    @SafeVarargs
    private static Map<String, Object> body(String note, Map<String, Object>... items) {
        return body(note, List.of(items));
    }

    private static Map<String, Object> body(String note, List<Map<String, Object>> items) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (note != null) {
            body.put("note", note);
        }
        body.put("requisitions", items);
        return body;
    }

    private static Map<String, Object> item(UUID requisition, UUID toUserId) {
        return Map.of("requisitionId", requisition, "toUserId", toUserId);
    }

    // A body with departments only.
    @SafeVarargs
    private static Map<String, Object> departmentBody(String note, Map<String, Object>... departments) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (note != null) {
            body.put("note", note);
        }
        body.put("departments", List.of(departments));
        return body;
    }

    private static Map<String, Object> departmentItem(UUID department, UUID toUserId) {
        return Map.of("departmentId", department, "toUserId", toUserId);
    }

    private UUID manager(UUID department) {
        return jdbc.queryForObject("SELECT manager_user_id FROM departments WHERE id = ?", UUID.class, department);
    }

    // Body errors that ApiExceptionHandler sends without a Cache-Control of its own still get no-store, from the
    // default headers of Spring Security.
    private void assertFieldError(HttpResponse<String> response, String field, String message) {
        JsonNode error = api.expect(response, 400);
        assertThat(error.path("code").asText()).as(field).isEqualTo("VALIDATION_ERROR");
        assertThat(error.path("message").asText()).isEqualTo("Vui lòng kiểm tra dữ liệu đã nhập.");
        assertThat(error.path("fieldErrors").size()).as(error.toString()).isOne();
        assertThat(error.path("fieldErrors").path(field).asText()).as(error.toString()).isEqualTo(message);
        assertThat(response.headers().firstValue("Cache-Control").orElse("<none>")).as(field).contains("no-store");
    }

    private void assertInvalidJson(HttpResponse<String> response) {
        assertThat(api.code(response, 400)).isEqualTo("INVALID_JSON");
        assertThat(response.headers().firstValue("Cache-Control").orElse("<none>")).contains("no-store");
    }

    private UUID account(String email, String fullName, Role role) {
        return accounts.saveAndFlush(new Account(email, fullName, passwordHash, Set.of(role), START)).getId();
    }

    // A requisition of two people needed by 1 Dec 2026, written with SQL so it can be in any status.
    private UUID requisition(RequisitionStatus status) {
        return requisition(status, itId);
    }

    private UUID requisition(RequisitionStatus status, UUID department) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO recruitment_requisitions (id, position_id, department_id, headcount, reason, needed_by, status,
                    created_by, created_at, updated_at)
                VALUES (?, ?, ?, 2, 'NEW_HEADCOUNT', ?, ?, ?, ?, ?)
                """, id, positionId, department, LocalDate.of(2026, 12, 1), status.name(), adminId,
                Timestamp.from(START), Timestamp.from(START));
        return id;
    }

    private void recruiter(UUID requisition, UUID recruiter, String role) {
        jdbc.update("""
                INSERT INTO requisition_recruiters (requisition_id, recruiter_id, assignment_role, assigned_by, assigned_at)
                VALUES (?, ?, ?, ?, ?)
                """, requisition, recruiter, role, adminId, Timestamp.from(START.minusSeconds(3600)));
    }

    // "recruiterId:ROLE", the primary first.
    private List<String> team(UUID requisition) {
        return jdbc.queryForList("""
                SELECT recruiter_id::text || ':' || assignment_role FROM requisition_recruiters
                WHERE requisition_id = ? ORDER BY assignment_role, recruiter_id
                """, String.class, requisition);
    }

    // "TYPE:recruiter:previous:note" in revision order, "-" for none; every change is by the administrator.
    private List<String> history(UUID requisition) {
        assertThat(jdbc.queryForList("SELECT DISTINCT changed_by FROM requisition_recruiter_changes WHERE requisition_id = ?",
                UUID.class, requisition)).allMatch(adminId::equals);
        return jdbc.queryForList("""
                SELECT change_type || ':' || recruiter_id || ':' || COALESCE(previous_recruiter_id::text, '-') || ':'
                    || COALESCE(note, '-')
                FROM requisition_recruiter_changes WHERE requisition_id = ? ORDER BY revision
                """, String.class, requisition);
    }

    private UUID sessionOf(UUID userId) {
        return jdbc.queryForObject("SELECT id FROM auth_sessions WHERE user_id = ? AND revoked_at IS NULL", UUID.class,
                userId);
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
