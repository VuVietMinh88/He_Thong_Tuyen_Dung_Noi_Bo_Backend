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
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.ttcs.recruitment.auth.ApprovalTestSupport.step;

// Task 263: each approval moves the requisition to the next level; the last one approves it (story S3-02).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RequisitionApprovalAdvanceIntegrationTest {
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
    private UUID hrId;
    private String hrToken;
    private String itHeadToken;
    private UUID approverA;
    private String approverAToken;
    private UUID approverB;
    private String approverBToken;
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
        UUID adminId = jdbc.queryForObject("SELECT id FROM user_accounts WHERE email = 'admin@example.test'", UUID.class);
        passwordHash = accounts.findById(adminId).orElseThrow().getPasswordHash();
        hrId = account("hr@example.test", "Nguyễn Thị Hoa", Role.HR_MANAGER);
        UUID itHead = account("it@example.test", "Trưởng IT", Role.HIRING_MANAGER);
        approverA = account("a@example.test", "Giám đốc A", Role.APPROVER);
        approverB = account("b@example.test", "Giám đốc B", Role.APPROVER);
        hrToken = api.token("hr@example.test");
        itHeadToken = api.token("it@example.test");
        approverAToken = api.token("a@example.test");
        approverBToken = api.token("b@example.test");
        it = api.department("IT", itHead, null, START);
        position = api.position(START);
    }

    @Test
    void aOneLevelChainIsApprovedByItsOnlyLevel() throws Exception {
        api.flow(it, hrId, START, step(1, null, null, "HR_MANAGER"));
        UUID id = api.submitted(position, it, 22_000_000L, itHeadToken, itHeadToken);

        assertThat(api.expect(api.decide(id, "APPROVE", null, 1, hrToken), 200).path("status").asText())
                .isEqualTo("APPROVED");

        // An approved requisition is listed as such, may be staffed, and is no longer edited or decided.
        JsonNode approved = api.expect(api.request("GET", "/api/v1/requisitions?status=APPROVED", null, hrToken), 200);
        assertThat(approved.path("items").get(0).path("id").asText()).isEqualTo(id.toString());
        UUID recruiter = account("r@example.test", "Recruiter", Role.RECRUITER);
        api.expect(api.request("POST", BASE + id + "/assign", json.writeValueAsString(Map.of("recruiterId", recruiter)),
                hrToken), 200);
        assertThat(api.code(api.request("PUT", BASE + id, json.writeValueAsString(content()), itHeadToken), 409))
                .isEqualTo("REQUISITION_NOT_DRAFT");
        assertThat(api.code(api.decide(id, "APPROVE", null, 2, hrToken), 409))
                .isEqualTo("REQUISITION_NOT_PENDING_APPROVAL");
    }

    @Test
    void eachLevelApprovesInTurnAndOnlyTheLastOneApprovesTheRequisition() throws Exception {
        api.flow(it, hrId, START, step(1, null, null, "HR_MANAGER"), step(2, 20_000_000L, approverA, null),
                step(3, 50_000_000L, null, "APPROVER"));
        UUID id = api.submitted(position, it, 60_000_000L, itHeadToken, itHeadToken);

        // Level 3's approver cannot act before levels 1 and 2.
        api.forbidden(api.decide(id, "APPROVE", null, 1, approverBToken));
        clock.set(START.plusSeconds(10));
        assertThat(status(api.decide(id, "APPROVE", "Đồng ý", 1, hrToken))).isEqualTo("PENDING_APPROVAL");
        // The same click again is outdated; with the new revision, HR still decided level 1 already.
        assertThat(api.code(api.decide(id, "APPROVE", null, 1, hrToken), 409)).isEqualTo("APPROVAL_STATE_CHANGED");
        api.forbidden(api.decide(id, "APPROVE", null, 2, hrToken));
        api.forbidden(api.decide(id, "APPROVE", null, 2, approverBToken));
        clock.set(START.plusSeconds(20));
        assertThat(status(api.decide(id, "APPROVE", "   ", 2, approverAToken))).isEqualTo("PENDING_APPROVAL");
        clock.set(START.plusSeconds(30));
        assertThat(status(api.decide(id, "APPROVE", "Đạt", 3, approverBToken))).isEqualTo("APPROVED");

        List<Map<String, Object>> history = jdbc.queryForList("""
                SELECT revision, event_type, level_number, status_after, actor_id, comment
                FROM requisition_workflow_events WHERE requisition_id = ? ORDER BY revision""", id);
        assertThat(history).extracting(row -> row.get("revision"), row -> row.get("level_number"),
                row -> row.get("status_after"), row -> row.get("actor_id")).containsExactly(
                org.assertj.core.groups.Tuple.tuple(1, null, "PENDING_APPROVAL", history.getFirst().get("actor_id")),
                org.assertj.core.groups.Tuple.tuple(2, 1, "PENDING_APPROVAL", hrId),
                org.assertj.core.groups.Tuple.tuple(3, 2, "PENDING_APPROVAL", approverA),
                org.assertj.core.groups.Tuple.tuple(4, 3, "APPROVED", approverB));
        // An optional comment is kept; a blank one is no comment.
        assertThat(history).extracting(row -> row.get("comment")).containsExactly(null, "Đồng ý", null, "Đạt");
    }

    @Test
    void aLevelDecidedByOnePersonIsNotTheirsAgainInTheSameRound() throws Exception {
        String hrApproverToken = tokenOf(account("hr-approver@example.test", "HR kiêm duyệt", Role.HR_MANAGER,
                Role.APPROVER), "hr-approver@example.test");
        api.flow(it, hrId, START, step(1, null, null, "HR_MANAGER"), step(2, 20_000_000L, null, "APPROVER"));
        UUID id = api.submitted(position, it, 22_000_000L, itHeadToken, itHeadToken);

        api.expect(api.decide(id, "APPROVE", null, 1, hrApproverToken), 200);
        api.forbidden(api.decide(id, "APPROVE", null, 2, hrApproverToken));
        assertThat(status(api.decide(id, "APPROVE", null, 2, approverAToken))).isEqualTo("APPROVED");
    }

    @Test
    void anotherHolderOfTheSameRoleWhoClicksLateIsToldToReload() throws Exception {
        api.flow(it, hrId, START, step(1, null, null, "APPROVER"), step(2, null, null, "HR_MANAGER"));
        UUID id = api.submitted(position, it, 22_000_000L, itHeadToken, itHeadToken);

        api.expect(api.decide(id, "APPROVE", null, 1, approverAToken), 200);
        // B can still read it (level 1 was routed to B too), so B gets a conflict to show, not a permission error.
        assertThat(api.code(api.decide(id, "APPROVE", null, 1, approverBToken), 409)).isEqualTo("APPROVAL_STATE_CHANGED");
        api.expect(api.request("GET", BASE + id, null, approverBToken), 200);
    }

    @Test
    void aRunningRoundKeepsTheApproversOfItsVersion() throws Exception {
        UUID flow = api.flow(it, hrId, START, step(1, null, null, "HR_MANAGER"), step(2, 20_000_000L, approverA, null));
        UUID id = api.submitted(position, it, 22_000_000L, itHeadToken, itHeadToken);
        api.publish(flow, 2, hrId, START, step(1, null, null, "HR_MANAGER"), step(2, 20_000_000L, approverB, null));

        api.expect(api.decide(id, "APPROVE", null, 1, hrToken), 200);
        api.forbidden(api.decide(id, "APPROVE", null, 2, approverBToken));
        assertThat(status(api.decide(id, "APPROVE", null, 2, approverAToken))).isEqualTo("APPROVED");
    }

    private String status(java.net.http.HttpResponse<String> response) {
        return api.expect(response, 200).path("status").asText();
    }

    private Map<String, Object> content() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("positionId", position);
        body.put("departmentId", it);
        body.put("headcount", 2);
        body.put("reason", "NEW_HEADCOUNT");
        return body;
    }

    private String tokenOf(UUID account, String email) throws Exception {
        return account == null ? null : api.token(email);
    }

    private UUID account(String email, String name, Role... roles) {
        return accounts.saveAndFlush(new Account(email, name, passwordHash, Set.of(roles), START)).getId();
    }
}
