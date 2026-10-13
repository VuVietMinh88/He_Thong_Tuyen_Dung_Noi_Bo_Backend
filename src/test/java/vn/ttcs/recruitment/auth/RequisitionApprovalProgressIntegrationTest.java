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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.account.BootstrapAdmin;
import vn.ttcs.recruitment.account.Role;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.ttcs.recruitment.auth.ApprovalTestSupport.step;

// Task 267: the approval progress and history of a requisition (story S3-03).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RequisitionApprovalProgressIntegrationTest {
    private static final Instant START = Instant.parse("2026-10-11T03:00:00Z");
    private static final String BASE = "/api/v1/requisitions/";

    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccountRepository accounts;
    @Autowired private BootstrapAdmin bootstrap;
    @Autowired private AuthIntegrationTest.MutableClock clock;
    @Autowired private PlatformTransactionManager transactions;

    private ApprovalTestSupport api;
    private String passwordHash;
    private UUID adminId;
    private UUID hrId;
    private String hrToken;
    private UUID hr2Id;
    private UUID itHeadId;
    private String itHeadToken;
    private String salesHeadToken;
    private UUID approverA;
    private String approverAToken;
    private String approverBToken;
    private String interviewerToken;
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
        clock.set(START);
        api = new ApprovalTestSupport(jdbc, json, transactions,
                Integer.parseInt(environment.getRequiredProperty("local.server.port")));
        api.reset();
        bootstrap.run(new DefaultApplicationArguments());
        adminId = jdbc.queryForObject("SELECT id FROM user_accounts WHERE email = 'admin@example.test'", UUID.class);
        passwordHash = accounts.findById(adminId).orElseThrow().getPasswordHash();
        hrId = account("hr@example.test", "Nguyễn Thị Hoa", Role.HR_MANAGER);
        hr2Id = account("hr2@example.test", "Trần Văn Nam", Role.HR_MANAGER);
        itHeadId = account("it@example.test", "Trưởng IT", Role.HIRING_MANAGER);
        UUID salesHead = account("sales@example.test", "Trưởng Kinh doanh", Role.HIRING_MANAGER);
        approverA = account("a@example.test", "Giám đốc A", Role.APPROVER);
        account("b@example.test", "Giám đốc B", Role.APPROVER);
        account("interviewer@example.test", "Người phỏng vấn", Role.INTERVIEWER);
        hrToken = api.token("hr@example.test");
        itHeadToken = api.token("it@example.test");
        salesHeadToken = api.token("sales@example.test");
        approverAToken = api.token("a@example.test");
        approverBToken = api.token("b@example.test");
        interviewerToken = api.token("interviewer@example.test");
        it = api.department("IT", itHeadId, null, START);
        api.department("SALES", salesHead, null, START);
        position = api.position(START);
    }

    @Test
    void aDraftNeverSubmittedHasNoRoundYet() throws Exception {
        UUID id = api.draft(position, it, 22_000_000L, itHeadToken);

        JsonNode progress = progress(id, itHeadToken);
        assertThat(progress.path("status").asText()).isEqualTo("DRAFT");
        assertThat(progress.path("revision").asInt()).isZero();
        assertThat(progress.path("createdById").asText()).isEqualTo(itHeadId.toString());
        assertThat(progress.path("createdByName").asText()).isEqualTo("Trưởng IT");
        for (String empty : new String[]{"round", "flow", "levelCount", "currentLevel", "submittedById", "waitingOn"}) {
            assertThat(progress.path(empty).isNull()).as(empty).isTrue();
        }
        assertThat(progress.path("levels").isEmpty()).isTrue();
        assertThat(progress.path("history").isEmpty()).isTrue();
        assertThat(progress.path("actions").path("canDecide").asBoolean()).isFalse();
    }

    @Test
    void aRequisitionWaitingAtLevelTwoShowsTheChainWhoItWaitsForAndTheHistory() throws Exception {
        api.flow(it, hrId, START, step(1, null, null, "HR_MANAGER"), step(2, 20_000_000L, approverA, null),
                step(3, 50_000_000L, null, "APPROVER"));
        UUID id = api.submitted(position, it, 60_000_000L, itHeadToken, itHeadToken);
        clock.set(START.plusSeconds(30));
        api.expect(api.decide(id, "APPROVE", "Đồng ý", 1, hrToken), 200);

        JsonNode progress = progress(id, itHeadToken);
        assertThat(progress.path("status").asText()).isEqualTo("PENDING_APPROVAL");
        assertThat(progress.path("revision").asInt()).isEqualTo(2);
        assertThat(progress.path("round").asInt()).isEqualTo(1);
        assertThat(progress.path("flow").path("version").asInt()).isEqualTo(1);
        assertThat(progress.path("flow").path("name").asText()).isEqualTo("Duyệt tuyển dụng 1");
        assertThat(progress.path("levelCount").asInt()).isEqualTo(3);
        assertThat(progress.path("currentLevel").asInt()).isEqualTo(2);
        assertThat(progress.path("submittedByName").asText()).isEqualTo("Trưởng IT");
        JsonNode levels = progress.path("levels");
        assertThat(texts(levels, "status")).containsExactly("APPROVED", "PENDING", "NOT_REACHED");
        assertThat(texts(levels, "stepName")).containsExactly("Cấp 1 · Trưởng phòng Nhân sự", "Cấp 2 · Giám đốc A",
                "Cấp 3 · Người duyệt");
        assertThat(texts(levels, "approverName")).containsExactly("Nguyễn Thị Hoa", "Giám đốc A", "Người duyệt");
        assertThat(texts(levels, "approverType")).containsExactly("ROLE", "USER", "ROLE");
        assertThat(levels.get(0).path("comment").asText()).isEqualTo("Đồng ý");
        assertThat(levels.get(0).path("decidedById").asText()).isEqualTo(hrId.toString());
        assertThat(levels.get(0).path("actionDate").asText()).isEqualTo("2026-10-11T03:00:30Z");
        assertThat(levels.get(1).path("addedBySalaryLimit").asBoolean()).isTrue();
        assertThat(levels.get(0).path("addedBySalaryLimit").asBoolean()).isFalse();
        JsonNode waitingOn = progress.path("waitingOn");
        assertThat(waitingOn.path("kind").asText()).isEqualTo("APPROVAL_LEVEL");
        assertThat(waitingOn.path("level").asInt()).isEqualTo(2);
        assertThat(waitingOn.path("hasEligibleApprover").asBoolean()).isTrue();
        assertThat(texts(waitingOn.path("people"), "fullName")).containsExactly("Giám đốc A");
        assertThat(texts(progress.path("history"), "action")).containsExactly("APPROVED", "SUBMITTED");
        assertThat(progress.path("history").get(1).path("salaryBasis").asLong()).isEqualTo(60_000_000L);
        assertThat(progress.path("history").get(1).path("levelCount").asInt()).isEqualTo(3);
        // The thresholds themselves are never shown.
        assertThat(progress.toString()).doesNotContain("salaryThreshold").doesNotContain("20000000");
        // Only the person who may decide level 2 now sees canDecide.
        assertThat(progress.path("actions").path("canDecide").asBoolean()).isFalse();
        assertThat(progress(id, approverAToken).path("actions").path("canDecide").asBoolean()).isTrue();
    }

    @Test
    void returnedApprovedAndRejectedRequisitionsShowTheirEnd() throws Exception {
        api.flow(it, hrId, START, step(1, null, null, "HR_MANAGER"));
        UUID returned = api.submitted(position, it, 22_000_000L, itHeadToken, itHeadToken);
        api.expect(api.decide(returned, "REQUEST_INFO", "Bổ sung", 1, hrToken), 200);
        JsonNode progress = progress(returned, hrToken);
        assertThat(progress.path("levels").get(0).path("status").asText()).isEqualTo("INFO_REQUESTED");
        assertThat(progress.path("currentLevel").isNull()).isTrue();
        assertThat(progress.path("waitingOn").path("kind").asText()).isEqualTo("REQUESTER");
        assertThat(texts(progress.path("waitingOn").path("people"), "userId")).containsExactly(itHeadId.toString());

        // The returned one is submitted again: two rounds, newest first.
        api.expect(api.request("POST", BASE + returned + "/submit", null, itHeadToken), 200);
        api.expect(api.decide(returned, "APPROVE", null, 3, hrToken), 200);
        progress = progress(returned, hrToken);
        assertThat(progress.path("status").asText()).isEqualTo("APPROVED");
        assertThat(progress.path("round").asInt()).isEqualTo(2);
        assertThat(progress.path("waitingOn").isNull()).isTrue();
        assertThat(texts(progress.path("history"), "action"))
                .containsExactly("APPROVED", "SUBMITTED", "INFO_REQUESTED", "SUBMITTED");
        assertThat(texts(progress.path("history"), "round")).containsExactly("2", "2", "1", "1");

        UUID rejected = api.submitted(position, it, 22_000_000L, itHeadToken, itHeadToken);
        api.expect(api.decide(rejected, "REJECT", "Không phù hợp", 1, hrToken), 200);
        progress = progress(rejected, itHeadToken);
        assertThat(progress.path("levels").get(0).path("status").asText()).isEqualTo("REJECTED");
        assertThat(progress.path("levels").get(0).path("comment").asText()).isEqualTo("Không phù hợp");
        assertThat(progress.path("waitingOn").isNull()).isTrue();
    }

    @Test
    void aLevelWhoseApproversWereLockedAfterSubmittingShowsThatNobodyCanDecideIt() throws Exception {
        api.flow(it, hrId, START, step(1, null, null, "HR_MANAGER"));
        UUID id = api.submitted(position, it, 22_000_000L, itHeadToken, itHeadToken);
        jdbc.update("UPDATE user_accounts SET admin_locked_at = ?, admin_lock_reason = 'Nghỉ', admin_locked_by = ? WHERE id IN (?, ?)",
                Timestamp.from(START), adminId, hrId, hr2Id);
        // A renamed creator shows the new name.
        jdbc.update("UPDATE user_accounts SET full_name = 'Trưởng phòng IT' WHERE id = ?", itHeadId);

        JsonNode progress = progress(id, itHeadToken);
        assertThat(progress.path("waitingOn").path("hasEligibleApprover").asBoolean()).isFalse();
        assertThat(progress.path("waitingOn").path("people").isEmpty()).isTrue();
        assertThat(progress.path("createdByName").asText()).isEqualTo("Trưởng phòng IT");
        assertThat(progress.path("history").get(0).path("actorName").asText()).isEqualTo("Trưởng phòng IT");
    }

    @Test
    void theProgressIsReadLikeTheRequisitionItself() throws Exception {
        api.flow(it, hrId, START, step(1, null, null, "HR_MANAGER"), step(2, 20_000_000L, approverA, null));
        UUID id = api.submitted(position, it, 22_000_000L, itHeadToken, itHeadToken);

        progress(id, hrToken);
        progress(id, itHeadToken);
        api.forbidden(api.request("GET", BASE + id + "/approvals", null, salesHeadToken));
        // Level 2 is not reached yet: A reads nothing until it is.
        api.forbidden(api.request("GET", BASE + id + "/approvals", null, approverAToken));
        api.expect(api.decide(id, "APPROVE", null, 1, hrToken), 200);
        progress(id, approverAToken);
        api.forbidden(api.request("GET", BASE + id + "/approvals", null, approverBToken));
        api.forbidden(api.request("GET", BASE + id + "/approvals", null, interviewerToken));
        assertThat(api.code(api.request("GET", BASE + UUID.randomUUID() + "/approvals", null, hrToken), 404))
                .isEqualTo("REQUISITION_NOT_FOUND");
        assertThat(api.request("GET", BASE + id + "/approvals", null, null).statusCode()).isEqualTo(401);
    }

    private JsonNode progress(UUID id, String token) throws Exception {
        var response = api.request("GET", BASE + id + "/approvals", null, token);
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        return api.expect(response, 200);
    }

    private static List<String> texts(JsonNode array, String field) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.path(field).asText()));
        return values;
    }

    private UUID account(String email, String name, Role... roles) {
        return accounts.saveAndFlush(new Account(email, name, passwordHash, Set.of(roles), START)).getId();
    }
}
